package com.pricetrack.exchange.custody.projection;

import static com.pricetrack.exchange.custody.model.CCustodyModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CCustodyReducerTest {
    static String a(int n) { return "0x"+String.format("%040x",n); }
    static String h(int n) { return "0x"+String.format("%064x",n); }
    static BigInteger n(long n) { return BigInteger.valueOf(n); }
    final String alice=a(1),bob=a(2),donor=a(3),token=a(10),vault=a(11),controller=a(12),rules=a(13),relay=a(14),admin=a(15);
    final Deployment deployment=new Deployment("C-trial",n(31337),h(100),token,vault,controller,rules,relay,admin,a(16),h(200));
    final Block anchor=new Block(0,h(100),h(0)), block=new Block(1,h(101),h(100));
    CCustodyReducer reducer; State state; int tx,index;
    @BeforeEach void setup() {
        reducer=new CCustodyReducer(new CCustodyReducer.Limits(100,20,10,10,10));
        state=reducer.initial(deployment,anchor); tx=0;index=0;
    }
    List<Log> logs(Event... events) {
        List<Log> result=new ArrayList<>();
        for(Event event:events) {
            String emitter=event instanceof Transfer?token:
                event instanceof RuleApplied||event instanceof ReportConsumed?rules:
                event instanceof UserExecuted||event instanceof RiskExecuted||event instanceof NonceCancelled||event instanceof PauseChanged?controller:vault;
            result.add(new Log(index++,emitter,event));
        }
        return result;
    }
    Bundle b(Call call,String sender,String to,Event... events) {
        int id=++tx; return new Bundle(deployment,block,h(1000+id),id,sender,to,Outcome.SUCCESS,call,logs(events));
    }
    State apply(Bundle b) { state=reducer.apply(state,b); return state; }
    Bundle deposit(String owner,long amount) { return b(new VaultCall(VaultAction.DEPOSIT,owner,n(amount)),owner,vault,
        new Transfer(owner,vault,n(amount)),new Deposited(owner,n(amount))); }
    Bundle fund(long amount) { return b(new VaultCall(VaultAction.FUND_HOUSE,donor,n(amount)),donor,vault,
        new Transfer(donor,vault,n(amount)),new HouseFunded(donor,n(amount))); }
    UserCall call(UserAction action,String owner,int op,int pos,long before,long after,long version) {
        return new UserCall(action,owner,h(op),h(pos),state.nonces().getOrDefault(owner,ZERO),n(version),n(before),n(after),h(700+op),h(800+op),true);
    }
    Bundle open(String owner,int op,int pos,long collateral) {
        UserCall c=call(UserAction.OPEN,owner,op,pos,0,collateral,0);
        return b(c,owner,controller,new RuleApplied(h(op),h(pos),"OPEN",ONE(),n(collateral),ZERO,ZERO,ZERO,ZERO),
            new ReportConsumed(h(op),n(1000),n(1030)),new Locked(h(op),h(pos),owner,n(collateral),n(collateral)),
            executed(c));
    }
    private static BigInteger ONE() { return BigInteger.ONE; }
    UserExecuted executed(UserCall c) { return new UserExecuted(c.operationId(),c.positionId(),c.owner(),c.intentDigest(),c.reportDigest(),c.action().name(),c.nonce(),c.direct()); }
    Bundle close(String owner,int op,int pos,long collateral,long pnl,long paid,int claim,long version) {
        UserCall c=call(UserAction.CLOSE,owner,op,pos,collateral,collateral,version);
        Position p=state.positions().get(h(pos));
        List<Event> e=new ArrayList<>(List.of(
            new RuleApplied(h(op),h(pos),"CLOSE",n(version+1),n(collateral),n(pnl),ZERO,p.callEpoch(),p.deadline()),
            new ReportConsumed(h(op),n(1000),n(1030))));
        if(claim!=0) e.add(new ClaimCreated(n(claim),owner,h(pos),h(op),n(pnl-paid)));
        e.add(new PositionSettled(h(op),h(pos),owner,n(collateral),n(pnl),ZERO,n(paid),n(claim))); e.add(executed(c));
        return b(c,owner,controller,e.toArray(Event[]::new));
    }
    Bundle risk(RiskAction action,int op,int pos,long pnl,long badDebt) {
        Position p=state.positions().get(h(pos));
        BigInteger epoch=action==RiskAction.START_CALL?p.callEpoch().add(ONE()):p.callEpoch();
        BigInteger deadline=action==RiskAction.START_CALL?n(1060):action==RiskAction.CLEAR_CALL?ZERO:p.deadline();
        RiskCall c=new RiskCall(action,p.owner(),h(op),h(pos),p.version(),p.collateral(),h(800+op));
        List<Event> e=new ArrayList<>(List.of(new RuleApplied(h(op),h(pos),action.name(),p.version().add(ONE()),p.collateral(),n(pnl),n(badDebt),epoch,deadline),
            new ReportConsumed(h(op),n(1060),n(1090))));
        if(action==RiskAction.LIQUIDATE) e.add(new PositionSettled(h(op),h(pos),p.owner(),p.collateral(),n(pnl),n(badDebt),ZERO,ZERO));
        e.add(new RiskExecuted(h(op),h(pos),p.owner(),c.reportDigest(),action.name()));
        return b(c,relay,controller,e.toArray(Event[]::new));
    }
    void rejectAtomic(Bundle bad) {
        State old=state; assertThrows(IllegalArgumentException.class,()->reducer.apply(old,bad));
        assertSame(old,state); assertEquals(old,state);
    }
    Bundle replace(Bundle original,List<Log> logs) { return new Bundle(original.deployment(),original.block(),original.transactionHash(),original.transactionIndex(),original.from(),original.to(),original.outcome(),original.call(),logs); }

    @Test void depositsHouseAndWithdrawAreSeparateActualCashFlows() {
        apply(deposit(alice,1000)); apply(fund(50));
        assertEquals(n(1000),state.totalFree()); assertEquals(n(50),state.house()); assertEquals(n(1050),state.actualAssets());
        apply(b(new VaultCall(VaultAction.WITHDRAW,alice,n(100)),alice,vault,
            new Transfer(vault,alice,n(100)),new Withdrawn(alice,n(100))));
        assertEquals(n(900),state.balances().get(alice).free()); assertEquals(n(950),state.actualAssets());
        assertEquals(ZERO,state.unassignedAssets()); assertFalse(state.completeCoverage());
    }
    @Test void openTopUpAndProfitableCloseConserveAssets() {
        apply(deposit(alice,1000)); apply(fund(50)); apply(open(alice,1,10,100));
        UserCall top=call(UserAction.TOP_UP,alice,2,10,100,150,1);
        apply(b(top,alice,controller,new RuleApplied(h(2),h(10),"TOP_UP",n(2),n(150),ZERO,ZERO,ZERO,ZERO),
            new ReportConsumed(h(2),n(1000),n(1030)),new Locked(h(2),h(10),alice,n(50),n(150)),executed(top)));
        apply(close(alice,3,10,150,25,25,0,2));
        assertEquals(n(1025),state.balances().get(alice).free()); assertEquals(ZERO,state.totalLocked());
        assertEquals(n(25),state.house()); assertEquals(Status.CLOSED,state.positions().get(h(10)).status());
        assertEquals(n(1050),state.recognizedAssets());
    }
    @Test void negativeLossAddsOnlyActualCollateralToHouseAndBadDebtIsNotCash() {
        apply(deposit(alice,1000)); apply(open(alice,1,10,100));
        apply(risk(RiskAction.START_CALL,2,10,0,0)); apply(risk(RiskAction.LIQUIDATE,3,10,-100,30));
        assertEquals(n(900),state.totalFree()); assertEquals(n(100),state.house()); assertEquals(n(1000),state.actualAssets());
        assertEquals(ZERO,state.totalUnpaid()); assertEquals(Status.LIQUIDATED,state.positions().get(h(10)).status());
    }
    @Test void insufficientHouseSeparatesTerminalPositionAndUnpaidClaim() {
        apply(deposit(alice,1000)); apply(fund(50)); apply(open(alice,1,10,100)); apply(close(alice,2,10,100,200,50,1,1));
        assertEquals(n(150),state.totalUnpaid()); assertEquals(n(1050),state.totalFree());
        assertEquals(n(150),state.claims().get(ONE()).original()); assertEquals(ZERO,state.totalLocked());
        assertEquals(Status.CLOSED,state.positions().get(h(10)).status()); assertEquals(ZERO,state.house());
    }
    @Test void fifoPartialPayoutCannotBeSkippedByNewProfit() {
        apply(deposit(alice,1000)); apply(deposit(bob,1000)); apply(fund(50));
        apply(open(alice,1,10,100)); apply(open(bob,2,11,100));
        apply(close(alice,3,10,100,200,50,1,1)); apply(fund(20));
        apply(close(bob,4,11,100,100,0,2,1));
        apply(b(new ProcessClaimCall(),donor,vault,new ClaimPaid(ONE(),alice,n(20),n(130))));
        assertEquals(ONE(),state.head()); assertEquals(n(230),state.totalUnpaid());
        assertEquals(n(1000),state.balances().get(bob).free());
        apply(fund(130)); apply(b(new ProcessClaimCall(),bob,vault,new ClaimPaid(ONE(),alice,n(130),ZERO)));
        assertEquals(n(2),state.head()); assertEquals(n(100),state.totalUnpaid());
        apply(fund(100)); apply(b(new ProcessClaimCall(),alice,vault,new ClaimPaid(n(2),bob,n(100),ZERO)));
        assertEquals(n(3),state.head()); assertEquals(ZERO,state.totalUnpaid());
    }
    @Test void freshDepositsAreNeverHouseAndCannotPayExistingClaims() {
        apply(deposit(alice,1000)); apply(open(alice,1,10,100)); apply(close(alice,2,10,100,20,0,1,1));
        apply(deposit(bob,1000)); apply(b(new ProcessClaimCall(),bob,vault));
        assertEquals(n(20),state.totalUnpaid()); assertEquals(ZERO,state.house()); assertEquals(n(2000),state.totalFree());
    }
    @Test void oneWeiRepaymentAndPaidHeadCannotBePaidTwice() {
        apply(deposit(alice,1000)); apply(open(alice,1,10,100)); apply(close(alice,2,10,100,1,0,1,1));
        apply(fund(1)); apply(b(new ProcessClaimCall(),bob,vault,new ClaimPaid(ONE(),alice,ONE(),ZERO)));
        Bundle duplicate=b(new ProcessClaimCall(),bob,vault,new ClaimPaid(ONE(),alice,ONE(),ZERO)); rejectAtomic(duplicate);
        apply(b(new ProcessClaimCall(),bob,vault)); assertEquals(n(1001),state.totalFree());
    }
    @Test void exactReplayIsIdempotentButSameKeyDifferentPayloadIsRejected() {
        Bundle original=deposit(alice,1000); apply(original); State same=state;
        assertSame(same,reducer.apply(same,original));
        List<Log> changed=List.of(new Log(original.logs().getFirst().index(),token,new Transfer(alice,vault,n(999))));
        rejectAtomic(replace(original,changed)); assertEquals(1,state.observations().size());
    }
    @Test void missingAndReorderedEventsRollBackEntireBundle() {
        apply(deposit(alice,1000)); Bundle open=open(alice,1,10,100);
        rejectAtomic(replace(open,open.logs().subList(0,2)));
        List<Log> bad=new ArrayList<>(open.logs()); Collections.swap(bad,0,1); rejectAtomic(replace(open,bad));
        assertEquals(n(1000),state.totalFree()); assertEquals(ZERO,state.totalLocked()); assertTrue(state.nonces().isEmpty());
    }
    @Test void insufficientFreeAndLossBelowCollateralAreRejectedAtomically() {
        apply(deposit(alice,50)); rejectAtomic(open(alice,1,10,100));
        apply(open(alice,2,10,50)); rejectAtomic(close(alice,3,10,50,-51,0,0,1));
        assertEquals(n(50),state.totalLocked()); assertEquals(Status.ACTIVE,state.positions().get(h(10)).status());
    }
    @Test void claimPriorityAndClaimOwnerMismatchAreRejected() {
        apply(deposit(alice,1000)); apply(deposit(bob,1000)); apply(open(alice,1,10,100)); apply(open(bob,2,11,100));
        apply(close(alice,3,10,100,100,0,1,1)); apply(fund(50));
        rejectAtomic(close(bob,4,11,100,20,20,0,1));
        rejectAtomic(b(new ProcessClaimCall(),bob,vault,new ClaimPaid(ONE(),bob,n(50),n(50))));
        assertEquals(n(50),state.house()); assertEquals(n(100),state.totalUnpaid());
    }
    @Test void unpaidAndPauseIndependentlyBlockOnlyOpen() {
        apply(deposit(alice,1000)); apply(open(alice,1,10,100)); apply(close(alice,2,10,100,20,0,1,1));
        rejectAtomic(open(alice,3,11,100));
        apply(b(new PauseCall(true),admin,controller,new PauseChanged(true)));
        apply(fund(20)); apply(b(new ProcessClaimCall(),bob,vault,new ClaimPaid(ONE(),alice,n(20),ZERO)));
        rejectAtomic(open(alice,4,11,100));
        apply(b(new VaultCall(VaultAction.WITHDRAW,alice,n(1)),alice,vault,new Transfer(vault,alice,n(1)),new Withdrawn(alice,n(1))));
        apply(b(new PauseCall(false),admin,controller,new PauseChanged(false))); apply(open(alice,5,11,100));
    }
    @Test void directRelayAndCancellationShareNonceButRiskDoesNotConsumeIt() {
        apply(deposit(alice,1000)); UserCall original=(UserCall)open(alice,1,10,100).call();
        UserCall relayCall=new UserCall(original.action(),original.owner(),original.operationId(),original.positionId(),original.nonce(),
            original.expectedVersion(),original.collateralBefore(),original.collateralAfter(),original.intentDigest(),original.reportDigest(),false);
        apply(b(relayCall,relay,controller,new RuleApplied(h(1),h(10),"OPEN",ONE(),n(100),ZERO,ZERO,ZERO,ZERO),
            new ReportConsumed(h(1),n(1000),n(1030)),new Locked(h(1),h(10),alice,n(100),n(100)),executed(relayCall)));
        apply(b(new CancelCall(alice,ONE()),alice,controller,new NonceCancelled(alice,ONE())));
        apply(risk(RiskAction.START_CALL,2,10,0,0)); apply(risk(RiskAction.CLEAR_CALL,3,10,0,0));
        apply(risk(RiskAction.START_CALL,4,10,0,0)); apply(risk(RiskAction.LIQUIDATE,5,10,-100,50));
        assertEquals(n(2),state.nonces().get(alice)); assertEquals(ZERO,state.totalLocked());
        rejectAtomic(b(new CancelCall(alice,ONE()),alice,controller,new NonceCancelled(alice,ONE())));
    }
    @Test void wrongCallerTargetAndOwnerCannotBeMadeValidByEvents() {
        apply(deposit(alice,1000)); Bundle open=open(alice,1,10,100);
        rejectAtomic(new Bundle(deployment,block,open.transactionHash(),open.transactionIndex(),relay,controller,Outcome.SUCCESS,open.call(),open.logs()));
        rejectAtomic(new Bundle(deployment,block,open.transactionHash(),open.transactionIndex(),alice,vault,Outcome.SUCCESS,open.call(),open.logs()));
        apply(open); rejectAtomic(b(new CancelCall(alice,ONE()),bob,controller,new NonceCancelled(alice,ONE())));
        rejectAtomic(b(new PauseCall(true),relay,controller,new PauseChanged(true)));
    }
    @Test void tokenDonationsAreUnassignedNotDepositOrHouse() {
        apply(deposit(alice,100)); apply(b(new DonationCall(n(7)),donor,token,new Transfer(donor,vault,n(7))));
        assertEquals(n(107),state.actualAssets()); assertEquals(n(100),state.recognizedAssets());
        assertEquals(n(7),state.unassignedAssets()); assertEquals(ZERO,state.house());
    }
    @Test void badTransferAmountAndEmitterCannotCreditFree() {
        Bundle deposit=deposit(alice,100);
        rejectAtomic(replace(deposit,List.of(new Log(0,token,new Transfer(alice,vault,n(99))),new Log(1,vault,new Deposited(alice,n(100))))));
        rejectAtomic(replace(deposit,List.of(new Log(0,vault,new Transfer(alice,vault,n(100))),new Log(1,vault,new Deposited(alice,n(100))))));
        assertTrue(state.balances().isEmpty());
    }
    @Test void deploymentMismatchForkGapAndTransactionReorderAreRejected() {
        Bundle first=deposit(alice,100); apply(first);
        Deployment foreign=new Deployment("other-C",n(31337),h(100),token,vault,controller,rules,relay,admin,a(16),h(200));
        Bundle next=deposit(bob,100);
        rejectAtomic(new Bundle(foreign,block,next.transactionHash(),next.transactionIndex(),bob,vault,Outcome.SUCCESS,next.call(),next.logs()));
        rejectAtomic(new Bundle(deployment,new Block(3,h(103),h(102)),next.transactionHash(),next.transactionIndex(),bob,vault,Outcome.SUCCESS,next.call(),next.logs()));
        rejectAtomic(new Bundle(deployment,new Block(1,h(199),h(100)),next.transactionHash(),next.transactionIndex(),bob,vault,Outcome.SUCCESS,next.call(),next.logs()));
        rejectAtomic(new Bundle(deployment,block,next.transactionHash(),first.transactionIndex(),bob,vault,Outcome.SUCCESS,next.call(),next.logs()));
    }
    @Test void restartReplayAndAlternateBranchRequirePreservedAncestor() {
        State ancestor=state; Bundle first=deposit(alice,100); State original=reducer.apply(ancestor,first);
        assertSame(original,new CCustodyReducer(new CCustodyReducer.Limits(100,20,10,10,10)).apply(original,first));
        Bundle otherBranch=new Bundle(deployment,new Block(1,h(199),h(100)),h(999),1,bob,vault,Outcome.SUCCESS,
            new VaultCall(VaultAction.DEPOSIT,bob,n(20)),List.of(new Log(0,token,new Transfer(bob,vault,n(20))),new Log(1,vault,new Deposited(bob,n(20)))));
        State rebuilt=reducer.apply(ancestor,otherBranch);
        assertEquals(n(20),rebuilt.totalFree()); assertEquals(n(100),original.totalFree());
        assertEquals(1,original.observations().size()); assertEquals(1,rebuilt.observations().size());
        assertThrows(IllegalArgumentException.class,()->reducer.apply(original,otherBranch));
        assertFalse(rebuilt.completeCoverage());
    }
    @Test void explicitEmptyBlocksAllowAncestryWithoutInventingMoney() {
        state=reducer.advanceEmptyBlock(state,block);
        assertEquals(ZERO,state.actualAssets()); assertFalse(state.completeCoverage());
        assertThrows(IllegalArgumentException.class,()->reducer.advanceEmptyBlock(state,new Block(3,h(103),h(102))));
    }
    @Test void revertedTransactionIsObservedWithoutFinancialEffectsOrNonceConsumption() {
        Bundle incoming=deposit(alice,100);
        Bundle reverted=new Bundle(deployment,block,incoming.transactionHash(),incoming.transactionIndex(),alice,vault,Outcome.REVERTED,incoming.call(),List.of());
        apply(reverted); assertEquals(ZERO,state.actualAssets()); assertEquals(1,state.observations().size());
        Bundle invalid=b(new VaultCall(VaultAction.DEPOSIT,bob,n(100)),bob,vault,new Deposited(bob,n(100)));
        rejectAtomic(new Bundle(deployment,block,invalid.transactionHash(),invalid.transactionIndex(),bob,vault,Outcome.REVERTED,invalid.call(),invalid.logs()));
    }
    @Test void largeIntegersRetainPrecisionAndUintOverflowCannotCommit() {
        BigInteger amount=BigInteger.TEN.pow(25).add(ONE());
        apply(b(new VaultCall(VaultAction.DEPOSIT,alice,amount),alice,vault,new Transfer(alice,vault,amount),new Deposited(alice,amount)));
        assertEquals(amount,state.totalFree());
        BigInteger tooLarge=UINT_MAX.add(ONE());
        rejectAtomic(b(new VaultCall(VaultAction.DEPOSIT,bob,tooLarge),bob,vault,new Transfer(bob,vault,tooLarge),new Deposited(bob,tooLarge)));
    }
    @Test void signedOverflowZeroPaymentsAndUnsupportedClaimEvidenceAreRejected() {
        apply(deposit(alice,1000)); apply(open(alice,1,10,100));
        Bundle close=close(alice,2,10,100,20,0,1,1);
        List<Log> bad=new ArrayList<>(close.logs()); RuleApplied old=(RuleApplied)bad.getFirst().event();
        bad.set(0,new Log(bad.getFirst().index(),rules,new RuleApplied(old.operationId(),old.positionId(),old.action(),old.version(),old.collateral(),BigInteger.ONE.shiftLeft(255),ZERO,ZERO,ZERO)));
        rejectAtomic(replace(close,bad));
        rejectAtomic(b(new ProcessClaimCall(),bob,vault,new ClaimPaid(ONE(),alice,ZERO,ZERO)));
        rejectAtomic(b(new ProcessClaimCall(),bob,vault,new ClaimCreated(ONE(),alice,h(10),h(99),ONE())));
    }
    @Test void boundsAndImmutableSnapshotsAreEnforced() {
        CCustodyReducer tiny=new CCustodyReducer(new CCustodyReducer.Limits(1,2,1,1,1));
        State first=tiny.apply(state,deposit(alice,100));
        assertThrows(IllegalArgumentException.class,()->tiny.apply(first,deposit(bob,100)));
        assertThrows(UnsupportedOperationException.class,()->first.balances().put(bob,new Balance(ONE(),ZERO)));
        assertTrue(first.verificationScope().contains("NOT verified")); assertFalse(first.completeCoverage());
    }
    @Test void sameTransactionHashCannotCreditAgainInLaterCanonicalBlock() {
        Bundle original=deposit(alice,100); apply(original);
        Bundle duplicate=new Bundle(deployment,new Block(2,h(102),h(101)),original.transactionHash(),0,
            original.from(),original.to(),original.outcome(),original.call(),original.logs());
        rejectAtomic(duplicate); assertEquals(n(100),state.actualAssets());
    }
    @Test void vaultSelfTransferCannotCreateDonationOrAttributedDeposit() {
        rejectAtomic(b(new DonationCall(n(10)),vault,token,new Transfer(vault,vault,n(10))));
        rejectAtomic(b(new VaultCall(VaultAction.DEPOSIT,vault,n(10)),vault,vault,
            new Transfer(vault,vault,n(10)),new Deposited(vault,n(10))));
        rejectAtomic(b(new VaultCall(VaultAction.FUND_HOUSE,vault,n(10)),vault,vault,
            new Transfer(vault,vault,n(10)),new HouseFunded(vault,n(10))));
        assertEquals(ZERO,state.actualAssets()); assertEquals(ZERO,state.house());
    }
    State seeded(Map<String,Balance> balances,Map<String,Position> positions,Map<BigInteger,Claim> claims,
            Map<String,BigInteger> nonces,Map<String,String> operations) {
        return new State(state.deployment(),state.cursor(),state.lastTransactionIndex(),balances,positions,claims,
            nonces,operations,state.totalFree(),state.totalLocked(),state.house(),state.actualAssets(),state.totalUnpaid(),
            state.head(),state.next(),state.paused(),state.observations());
    }
    void seedRejected(State seeded,String reason) {
        var failure=assertThrows(IllegalArgumentException.class,
            ()->reducer.advanceEmptyBlock(seeded,new Block(2,h(102),h(101))));
        assertEquals(reason,failure.getMessage());
    }
    @Test void seedRejectsNullStatusesDuplicateActiveOwnerAndMalformedOperationKeys() {
        apply(deposit(alice,1000)); apply(open(alice,1,10,100));
        Position p=state.positions().get(h(10));
        seedRejected(seeded(state.balances(),Map.of(h(10),new Position(alice,p.collateral(),p.version(),ZERO,ZERO,null)),
            state.claims(),state.nonces(),state.usedOperations()),"C_POSITION_STATUS");
        Position split=new Position(alice,n(50),p.version(),ZERO,ZERO,Status.ACTIVE);
        seedRejected(seeded(state.balances(),Map.of(h(10),split,h(11),split),state.claims(),state.nonces(),state.usedOperations()),"C_ACTIVE_POSITION");
        seedRejected(seeded(state.balances(),state.positions(),state.claims(),state.nonces(),Map.of("not-a-hash",h(10))),"C_HASH");
        seedRejected(seeded(state.balances(),state.positions(),state.claims(),state.nonces(),Map.of(h(9),h(99))),"C_OPERATION_SEED");
    }
    @Test void seedNonceAndOperationMapsCannotBypassCollectionBounds() {
        apply(deposit(alice,100));
        Map<String,BigInteger> tooManyNonces=new HashMap<>();
        for(int k=1;k<=11;k++) tooManyNonces.put(a(k),ZERO);
        seedRejected(seeded(state.balances(),state.positions(),state.claims(),tooManyNonces,state.usedOperations()),"C_STATE_LIMIT");
        Map<String,String> tooManyOperations=new HashMap<>();
        for(int k=1;k<=101;k++) tooManyOperations.put(h(k),h(999));
        seedRejected(seeded(state.balances(),state.positions(),state.claims(),state.nonces(),tooManyOperations),"C_STATE_LIMIT");
    }
    @Test void seedCannotPayLaterClaimAheadOfUnpaidHeadEvenWhenCashTotalsBalance() {
        apply(deposit(alice,1000)); apply(deposit(bob,1000));
        apply(open(alice,1,10,100)); apply(open(bob,2,11,100));
        apply(close(alice,3,10,100,100,0,1,1)); apply(close(bob,4,11,100,50,0,2,1)); apply(fund(1));
        Map<BigInteger,Claim> altered=new HashMap<>(state.claims()); Claim second=altered.get(n(2));
        altered.put(n(2),new Claim(bob,second.operationId(),second.positionId(),n(50),ONE(),n(49)));
        Map<String,Balance> balances=new HashMap<>(state.balances()); balances.put(bob,new Balance(n(1001),ZERO));
        State invalid=new State(deployment,state.cursor(),state.lastTransactionIndex(),balances,state.positions(),altered,
            state.nonces(),state.usedOperations(),n(2001),ZERO,ZERO,n(2001),n(149),ONE(),n(3),false,state.observations());
        seedRejected(invalid,"C_FIFO_SEED");
    }
}
