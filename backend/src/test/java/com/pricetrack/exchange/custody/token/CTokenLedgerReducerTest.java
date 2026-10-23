package com.pricetrack.exchange.custody.token;

import static com.pricetrack.exchange.custody.token.CTokenLedgerModels.*;
import static org.junit.jupiter.api.Assertions.*;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.Event;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.Transfer;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.Approval;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.Deposited;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CTokenLedgerReducerTest {
    static BigInteger n(long n){return BigInteger.valueOf(n);}
    static String a(int n){return "0x"+String.format("%040x",n);}
    static String h(int n){return "0x"+String.format("%064x",n);}
    final String treasury=a(1),alice=a(2),bob=a(3),vault=a(11),token=a(10);
    final Block anchor=new Block(n(0),h(100),h(0)),block=new Block(n(1),h(101),h(100));
    final Deployment deployment=new Deployment("C-token",n(31337),h(100),token,treasury,h(300),block,h(500),n(0),n(0));
    CTokenLedgerReducer reducer;State state;int sequence,logIndex;
    @BeforeEach void setup(){reducer=new CTokenLedgerReducer(new CTokenLedgerReducer.Limits(100,32,32,32));state=reducer.initial(deployment,anchor);sequence=0;logIndex=1;}
    Transaction mint(){return new Transaction(deployment,block,h(500),n(0),Outcome.SUCCESS,List.of(new Log(n(0),token,new Transfer(ZERO_ADDRESS,treasury,FIXED_SUPPLY))));}
    State apply(Transaction tx){state=reducer.apply(state,tx);return state;}
    void minted(){apply(mint());}
    Transaction tx(Event... events){List<Log> logs=new ArrayList<>();for(Event e:events)logs.add(new Log(n(logIndex++),token,e));int id=++sequence;return new Transaction(deployment,block,h(500+id),n(id),Outcome.SUCCESS,logs);}
    Transaction changed(Transaction tx,List<Log> logs){return new Transaction(tx.deployment(),tx.block(),tx.hash(),tx.index(),tx.outcome(),logs);}
    void rejectAtomic(Transaction tx){State old=state;assertThrows(IllegalArgumentException.class,()->reducer.apply(old,tx));assertSame(old,state);}
    void sum(){assertEquals(state.totalSupply(),state.balances().values().stream().reduce(BigInteger.ZERO,BigInteger::add));}

    @Test void constructorMintIsExactlyFixedSupplyToManifestTreasuryOnce(){minted();assertTrue(state.minted());assertEquals(FIXED_SUPPLY,state.totalSupply());assertEquals(FIXED_SUPPLY,state.balanceOf(treasury));sum();}
    @Test void wrongFirstMintAmountOwnerOrLogLocationIsRejected(){
        Transaction mint=mint();
        rejectAtomic(changed(mint,List.of(new Log(n(0),token,new Transfer(ZERO_ADDRESS,treasury,FIXED_SUPPLY.subtract(n(1)))))));
        rejectAtomic(changed(mint,List.of(new Log(n(0),token,new Transfer(ZERO_ADDRESS,alice,FIXED_SUPPLY)))));
        rejectAtomic(changed(mint,List.of(new Log(n(1),token,new Transfer(ZERO_ADDRESS,treasury,FIXED_SUPPLY)))));
        assertFalse(state.minted());assertEquals(BigInteger.ZERO,state.totalSupply());
    }
    @Test void firstMintRequiresExactDeploymentTransactionAndBlockIdentity(){
        Transaction m=mint();rejectAtomic(new Transaction(deployment,block,h(999),m.index(),m.outcome(),m.logs()));
        rejectAtomic(new Transaction(deployment,block,m.hash(),n(1),m.outcome(),m.logs()));
        rejectAtomic(new Transaction(deployment,new Block(n(1),h(999),h(100)),m.hash(),m.index(),m.outcome(),m.logs()));
    }
    @Test void repeatedMintAndTwoMintsInOneTransactionCannotIncreaseSupply(){
        Transaction m=mint();rejectAtomic(changed(m,List.of(m.logs().getFirst(),new Log(n(1),token,new Transfer(ZERO_ADDRESS,treasury,FIXED_SUPPLY)))));
        minted();rejectAtomic(tx(new Transfer(ZERO_ADDRESS,alice,n(0))));rejectAtomic(tx(new Transfer(ZERO_ADDRESS,alice,n(1))));sum();
    }
    @Test void burnsAndZeroReceiverAreRejectedEvenForZeroAmount(){minted();rejectAtomic(tx(new Transfer(treasury,ZERO_ADDRESS,n(1))));rejectAtomic(tx(new Transfer(treasury,ZERO_ADDRESS,n(0))));sum();}
    @Test void ownerTransfersConserveSupplyAndKeepExactIntegerBalances(){
        minted();BigInteger amount=BigInteger.TEN.pow(24).add(n(1));apply(tx(new Transfer(treasury,alice,amount)));apply(tx(new Transfer(alice,bob,n(1))));
        assertEquals(amount.subtract(n(1)),state.balanceOf(alice));assertEquals(n(1),state.balanceOf(bob));assertEquals(FIXED_SUPPLY,state.totalSupply());sum();
    }
    @Test void selfTransferKeepsBalanceButStillRequiresSufficientFunds(){
        minted();apply(tx(new Transfer(treasury,alice,n(100))));apply(tx(new Transfer(alice,alice,n(100))));
        assertEquals(n(100),state.balanceOf(alice));rejectAtomic(tx(new Transfer(alice,alice,n(101))));assertEquals(3,state.observations().size());sum();
    }
    @Test void zeroTransfersIncludingUnfundedSelfRemainObservedWithoutInventingMoney(){
        minted();apply(tx(new Transfer(alice,bob,n(0)),new Transfer(bob,bob,n(0))));
        assertEquals(n(0),state.balanceOf(alice));assertEquals(n(0),state.balanceOf(bob));assertEquals(2,state.observations().size());sum();
    }
    @Test void approvalValuesNeverChangeBalancesOrProveCurrentAllowance(){
        minted();State before=state;apply(tx(new Approval(alice,vault,CAbiModels.UINT_MAX)));
        assertEquals(before.balances(),state.balances());assertEquals(before.totalSupply(),state.totalSupply());
        assertEquals(CAbiModels.UINT_MAX,state.lastObservedApprovals().get(new ApprovalKey(alice,vault)).observedValue());assertFalse(state.currentAllowanceVerified());
        apply(tx(new Approval(alice,vault,n(0))));assertEquals(n(0),state.lastObservedApprovals().get(new ApprovalKey(alice,vault)).observedValue());
        assertEquals(3,state.observations().size());
    }
    @Test void transferFromWithoutApprovalLogDoesNotRewriteLastApprovalObservation(){
        minted();apply(tx(new Transfer(treasury,alice,n(100))));apply(tx(new Approval(alice,vault,n(100))));
        ApprovalObservation last=state.lastObservedApprovals().get(new ApprovalKey(alice,vault));
        apply(tx(new Transfer(alice,vault,n(25))));assertEquals(last,state.lastObservedApprovals().get(new ApprovalKey(alice,vault)));
        assertEquals(n(75),state.balanceOf(alice));assertEquals(n(25),state.balanceOf(vault));assertFalse(state.currentAllowanceVerified());
    }
    @Test void approvalOwnerAndSpenderCannotBeZero(){minted();rejectAtomic(tx(new Approval(ZERO_ADDRESS,alice,n(0))));rejectAtomic(tx(new Approval(alice,ZERO_ADDRESS,n(0))));assertTrue(state.lastObservedApprovals().isEmpty());}
    @Test void directVaultDonationIsOnlyTokenOwnershipMovementNotVaultFreeOrHouse(){
        minted();apply(tx(new Transfer(treasury,vault,n(100))));assertEquals(n(100),state.balanceOf(vault));
        assertTrue(state.verificationScope().contains("Vault attribution"));assertFalse(state.completeCoverage());sum();
    }
    @Test void entireTransactionRollsBackWhenLaterTransferOrApprovalIsInvalid(){
        minted();State original=state;
        rejectAtomic(tx(new Transfer(treasury,alice,n(100)),new Transfer(alice,bob,n(101))));
        rejectAtomic(tx(new Transfer(treasury,alice,n(100)),new Approval(alice,ZERO_ADDRESS,n(1))));
        assertEquals(original,state);assertEquals(n(0),state.balanceOf(alice));assertEquals(1,state.observations().size());
    }
    @Test void exactReplayIsIdempotentButSameKeyDifferentPayloadIsConflict(){
        Transaction m=mint();minted();assertSame(state,reducer.apply(state,m));Transaction move=tx(new Transfer(treasury,alice,n(100)));apply(move);
        assertSame(state,reducer.apply(state,move));
        rejectAtomic(changed(move,List.of(new Log(move.logs().getFirst().index(),token,new Transfer(treasury,alice,n(99))))));
    }
    @Test void sameTransactionHashCannotBeReincludedOnCurrentBranch(){
        minted();Transaction move=tx(new Transfer(treasury,alice,n(100)));apply(move);
        rejectAtomic(new Transaction(deployment,new Block(n(2),h(102),h(101)),move.hash(),n(0),move.outcome(),move.logs()));
    }
    @Test void differentDeploymentChainTokenOrTreasuryCannotReuseProjection(){
        minted();Transaction move=tx(new Transfer(treasury,alice,n(100)));
        Deployment foreign=new Deployment("other",n(31338),h(100),a(99),bob,h(300),block,h(500),n(0),n(0));
        rejectAtomic(new Transaction(foreign,block,move.hash(),move.index(),move.outcome(),move.logs()));
    }
    @Test void wrongEmitterUnknownEventAndRevertedLogsAreRejected(){
        minted();Transaction move=tx(new Transfer(treasury,alice,n(100)));
        rejectAtomic(changed(move,List.of(new Log(move.logs().getFirst().index(),a(99),move.logs().getFirst().event()))));
        rejectAtomic(tx(new Deposited(alice,n(100))));
        rejectAtomic(new Transaction(deployment,block,move.hash(),move.index(),Outcome.REVERTED,move.logs()));
        apply(new Transaction(deployment,block,move.hash(),move.index(),Outcome.REVERTED,List.of()));assertEquals(n(0),state.balanceOf(alice));
    }
    @Test void transactionAndLogOrderingRejectsGapsForksAndDuplicates(){
        minted();Transaction move=tx(new Transfer(treasury,alice,n(100)));apply(move);
        Transaction next=tx(new Transfer(alice,bob,n(1)));
        rejectAtomic(new Transaction(deployment,block,next.hash(),move.index(),next.outcome(),next.logs()));
        rejectAtomic(new Transaction(deployment,new Block(n(3),h(103),h(102)),next.hash(),next.index(),next.outcome(),next.logs()));
        rejectAtomic(new Transaction(deployment,new Block(n(1),h(199),h(100)),next.hash(),next.index(),next.outcome(),next.logs()));
        rejectAtomic(changed(next,List.of(new Log(move.logs().getFirst().index(),token,next.logs().getFirst().event()))));
        rejectAtomic(changed(next,List.of(new Log(n(20),token,new Transfer(alice,bob,n(0))),new Log(n(20),token,new Approval(alice,bob,n(0))))));
    }
    @Test void collectionAndLogLimitsRejectWithoutPartialCommit(){
        CTokenLedgerReducer tiny=new CTokenLedgerReducer(new CTokenLedgerReducer.Limits(1,1,1,1));
        State first=tiny.apply(tiny.initial(deployment,anchor),mint());Transaction next=tx(new Transfer(treasury,alice,n(0)));
        assertThrows(IllegalArgumentException.class,()->tiny.apply(first,next));
        CTokenLedgerReducer oneHolder=new CTokenLedgerReducer(new CTokenLedgerReducer.Limits(100,1,1,1));
        assertThrows(IllegalArgumentException.class,()->oneHolder.apply(first,next));
        minted();rejectAtomic(tx(Collections.nCopies(33,new Approval(alice,bob,n(0))).toArray(Event[]::new)));
        assertThrows(IllegalArgumentException.class,()->new CTokenLedgerReducer.Limits(Integer.MAX_VALUE,1,1,1));
    }
    @Test void immutableStateCopiesDoNotShareMutableInputsOrExposeWritableMaps(){
        minted();assertThrows(UnsupportedOperationException.class,()->state.balances().put(alice,n(1)));
        assertThrows(UnsupportedOperationException.class,()->state.observations().clear());
        List<Log> mutable=new ArrayList<>(List.of(new Log(n(1),token,new Approval(alice,bob,n(0)))));
        Transaction transaction=new Transaction(deployment,block,h(501),n(1),Outcome.SUCCESS,mutable);mutable.clear();
        apply(transaction);assertEquals(1,state.lastObservedApprovals().size());
    }
    @Test void emptyBlockAdvanceRequiresMintAndExactParentButDoesNotClaimCoverage(){
        assertThrows(IllegalArgumentException.class,()->reducer.advanceEmptyBlock(state,block));
        minted();state=reducer.advanceEmptyBlock(state,new Block(n(2),h(102),h(101)));
        assertEquals(FIXED_SUPPLY,state.totalSupply());assertEquals(n(-1),state.lastTransactionIndex());assertFalse(state.completeCoverage());
        assertThrows(IllegalArgumentException.class,()->reducer.advanceEmptyBlock(state,new Block(n(4),h(104),h(103))));
        assertEquals(3,state.headers().size());
        State forgedCursor=new State(deployment,new Block(n(2),h(999),h(101)),n(-1),true,FIXED_SUPPLY,
            state.balances(),Map.of(),state.observations(),state.headers());
        assertThrows(IllegalArgumentException.class,()->reducer.advanceEmptyBlock(forgedCursor,new Block(n(3),h(103),h(999))));
        State gap=new State(deployment,state.cursor(),n(-1),true,FIXED_SUPPLY,state.balances(),Map.of(),state.observations(),List.of(anchor,state.cursor()));
        assertThrows(IllegalArgumentException.class,()->reducer.advanceEmptyBlock(gap,new Block(n(3),h(103),h(102))));
    }
    @Test void independentAncestorReplayPreservesOldObservationsInsteadOfSelectingCanonicalFork(){
        State ancestor=state;Transaction m=mint();State original=reducer.apply(ancestor,m);
        assertEquals(original,reducer.apply(ancestor,m));assertFalse(original.completeCoverage());
        Deployment alternate=new Deployment("C-other-branch",n(31337),h(100),token,treasury,h(300),new Block(n(1),h(199),h(100)),h(500),n(0),n(0));
        State fresh=reducer.initial(alternate,anchor);Transaction alternative=new Transaction(alternate,alternate.deploymentBlock(),h(500),n(0),Outcome.SUCCESS,m.logs());
        State rebuilt=reducer.apply(fresh,alternative);assertEquals(FIXED_SUPPLY,rebuilt.totalSupply());assertEquals(1,original.observations().size());
        assertThrows(IllegalArgumentException.class,()->reducer.apply(original,alternative));
    }
    @Test void uint256AndSeedSupplyOrMintProvenanceCannotBeForged(){
        assertThrows(IllegalArgumentException.class,()->new Transfer(treasury,alice,n(-1)));
        assertThrows(IllegalArgumentException.class,()->new Approval(alice,bob,CAbiModels.UINT_MAX.add(n(1))));
        minted();State invalid=new State(deployment,state.cursor(),state.lastTransactionIndex(),true,FIXED_SUPPLY,
            Map.of(treasury,FIXED_SUPPLY.add(n(1))),Map.of(),state.observations(),state.headers());
        assertThrows(IllegalArgumentException.class,()->reducer.advanceEmptyBlock(invalid,new Block(n(2),h(102),h(101))));
        State redistributed=new State(deployment,state.cursor(),state.lastTransactionIndex(),true,FIXED_SUPPLY,
            Map.of(treasury,FIXED_SUPPLY.subtract(n(1)),alice,n(1)),Map.of(),state.observations(),state.headers());
        var forged=assertThrows(IllegalArgumentException.class,()->reducer.advanceEmptyBlock(redistributed,new Block(n(2),h(102),h(101))));
        assertEquals("C_TOKEN_BALANCE_OBSERVATIONS",forged.getMessage());
        State missing=new State(deployment,state.cursor(),state.lastTransactionIndex(),true,FIXED_SUPPLY,state.balances(),Map.of(),Map.of(),state.headers());
        assertThrows(IllegalArgumentException.class,()->reducer.advanceEmptyBlock(missing,new Block(n(2),h(102),h(101))));
    }
    @Test void seedCannotCallEarlierApprovalCurrentLatestObservation(){
        minted();apply(tx(new Approval(alice,bob,n(100))));Map<ApprovalKey,ApprovalObservation> stale=state.lastObservedApprovals();
        apply(tx(new Approval(alice,bob,n(0))));
        State invalid=new State(deployment,state.cursor(),state.lastTransactionIndex(),true,FIXED_SUPPLY,state.balances(),stale,state.observations(),state.headers());
        var failure=assertThrows(IllegalArgumentException.class,()->reducer.advanceEmptyBlock(invalid,new Block(n(2),h(102),h(101))));
        assertEquals("C_TOKEN_LATEST_APPROVAL_OBSERVATIONS",failure.getMessage());assertFalse(state.currentAllowanceVerified());
    }
    @Test void constructorMintAndPostMintTransferCanShareOneAtomicTransaction(){
        Transaction m=mint();apply(changed(m,List.of(m.logs().getFirst(),new Log(n(1),token,new Transfer(treasury,alice,n(1))))));
        assertEquals(n(1),state.balanceOf(alice));assertEquals(FIXED_SUPPLY.subtract(n(1)),state.balanceOf(treasury));sum();
    }
}
