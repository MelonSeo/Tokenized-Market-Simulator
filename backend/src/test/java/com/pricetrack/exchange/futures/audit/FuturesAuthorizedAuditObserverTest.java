package com.pricetrack.exchange.futures.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.reserve.ReserveModels.DbCut;
import com.pricetrack.exchange.reserve.ReserveModels.Balance;
import com.pricetrack.exchange.reserve.ReserveModels.Grant;
import com.pricetrack.exchange.futures.audit.FuturesAuditDeployment.*;
import com.pricetrack.exchange.futures.audit.FuturesAuditModels.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Deliberate local signatures/RPC fixtures, NOT PostgreSQL, Anvil or browser acceptance. */
class FuturesAuthorizedAuditObserverTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final FuturesAuditStore store=mock(FuturesAuditStore.class);
    final FuturesAuditDeploymentStore deployments=mock(FuturesAuditDeploymentStore.class);
    final FuturesRpcGateway rpc=mock(FuturesRpcGateway.class);
    final FuturesChainIdentity chain=FuturesAuthorizedAuditVerifierTest.V2;
    final String CH="0x"+"a1".repeat(32),RH="0x"+"a2".repeat(32),B1="0x"+"b1".repeat(32),B2="0x"+"b2".repeat(32);
    final FuturesAuditDeployment target=FuturesAuditDeployment.target(Route.B_V2,"fixture-only",chain,CH,RH,FuturesLedger.POLICY_HASH);
    FuturesAuthorizedAuditObserver observer;FuturesAuditStore.Cut cut;Baseline baseline;
    @BeforeEach void setup()throws Exception{
        observer=new FuturesAuthorizedAuditObserver(store,deployments,List.of(new FuturesAuthorizedAuditObserver.Context(target,rpc)));
        baseline=new Baseline("base",target.executionId(),chain,RH,target.policyHash(),FuturesLedger.INITIAL_CAPITAL.toString(),new Block(1,B1),Instant.EPOCH,"db",1);
        when(deployments.find(target)).thenReturn(Optional.of(new Envelope(target,baseline)));
        when(deployments.register(any())).thenAnswer(i->i.getArgument(0));
        when(store.fingerprint(any())).thenReturn("db");setCut(FuturesAuditAccountingTest.empty());
        when(rpc.height()).thenReturn(BigInteger.TWO);
        when(rpc.block(BigInteger.ZERO)).thenReturn(block(0,"0x"+"b0".repeat(32)));
        when(rpc.block(BigInteger.ONE)).thenReturn(block(1,B1));when(rpc.block(BigInteger.TWO)).thenReturn(block(2,B2));
        when(rpc.deploymentCodeHashes(any())).thenReturn(Map.of("controller",CH,"rules",RH));
        when(rpc.auditLogs(any(),any())).thenReturn(List.of());when(rpc.auditTransactions(any())).thenReturn(List.of());
    }
    void setCut(FuturesAuditSnapshot s){cut=new FuturesAuditStore.Cut(s,new DbCut(Instant.EPOCH,List.of(),List.of(),List.of(),List.of(),List.of(),FuturesAuditAccounting.impact(s,json)));when(store.authorizedCut()).thenReturn(cut);}
    EthBlock.Block block(int n,String hash){var b=new EthBlock.Block();b.setNumber(Numeric.encodeQuantity(BigInteger.valueOf(n)));b.setHash(hash);b.setTimestamp("0x3f2");return b;}
    @Test void emptyRegisteredTargetIsExplicitCompleteMatch(){var r=observer.observe(1);assertThat(r.status()).isEqualTo("MATCH");assertThat(r.complete()).isTrue();assertThat(r.targets()).hasSize(1);assertThat(r.targets().get(0).coverage().transactions()).isZero();assertThat(r.limitation()).contains("not ERC-20 escrow");}
    @Test void baselineRequiredAndCodeMismatchNeverBecomeMatch()throws Exception{
        when(deployments.find(target)).thenReturn(Optional.empty());assertThat(observer.observe(1).reason()).isEqualTo("PARTIAL_TARGET_COVERAGE");
        when(rpc.deploymentCodeHashes(any())).thenReturn(Map.of("controller",RH,"rules",RH));assertThat(observer.observe(1).status()).isEqualTo("INCONCLUSIVE");
    }
    @Test void movingCutAndMissingHistoryAreInconclusive()throws Exception{
        when(store.fingerprint(any())).thenReturn("first","second");assertThat(observer.observe(1).reason()).isEqualTo("CUT_CHANGED");
        when(store.fingerprint(any())).thenReturn("db");when(rpc.auditTransactions(any())).thenThrow(new java.io.IOException("private-rpc-secret"));
        var r=observer.observe(1);assertThat(r.status()).isEqualTo("INCONCLUSIVE");assertThat(json.writeValueAsString(r)).doesNotContain("private-rpc-secret","rawTransaction","userSignature");
    }
    @Test void trueReverseOrphanIsMismatchButLegitimateCancellationIsInconclusive()throws Exception{
        var tx=new Transaction();tx.setHash("0x"+"f1".repeat(32));tx.setBlockHash(B2);tx.setBlockNumber("0x2");when(rpc.auditTransactions(BigInteger.TWO)).thenReturn(List.of(tx));
        assertThat(observer.observe(1).status()).isEqualTo("MISMATCH");
        var log=new Log();log.setAddress(chain.engine());log.setTransactionHash(tx.getHash());log.setBlockNumber("0x2");log.setBlockHash(B2);log.setTopics(List.of(Hash.sha3String("UserNonceCancelledV2(address,uint256)")));when(rpc.auditLogs(any(),any())).thenReturn(List.of(log));
        var r=observer.observe(1);assertThat(r.status()).isEqualTo("INCONCLUSIVE");assertThat(r.reason()).isEqualTo("CANCEL_COVERAGE_REQUIRED");assertThat(r.complete()).isFalse();
    }
    @Test void pendingDoesNotTouchRpcOrRunMutation(){
        var s=FuturesAuditAccountingTest.empty();setCut(new FuturesAuditSnapshot(s.policy(),s.initial(),s.house(),s.reserved(),List.of(FuturesAuditAccountingTest.position("OPEN_PENDING",0,"o")),List.of(FuturesAuditAccountingTest.open("PREPARED")),List.of(),List.of(),List.of()));
        assertThat(observer.observe(1).reason()).isEqualTo("UNSETTLED_OR_REVIEW");verifyNoInteractions(rpc);verify(store,never()).create(any());verify(store,never()).save(any());verify(deployments,never()).register(any());
    }
    @Test void freshTargetBaselineChecksBothAddressesAndPreservesLegacyBaseline()throws Exception{
        when(deployments.find(target)).thenReturn(Optional.empty());var e=observer.baseline(target.key(),1);
        assertThat(e.baseline().codeHash()).isEqualTo(RH);verify(rpc).auditLogs(BigInteger.ZERO,BigInteger.TWO);verify(rpc).auditTransactions(BigInteger.ZERO);verify(rpc).auditTransactions(BigInteger.ONE);verify(rpc).auditTransactions(BigInteger.TWO);verify(store,never()).baseline();verify(store,never()).create(any());
    }
    @Test void cancellationWhileFinalStableRpcReturnsCannotRegisterBaseline()throws Exception {
        when(deployments.find(target)).thenReturn(Optional.empty());
        var cancellation=new FuturesAuditCancellation(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(rpc.height()).thenAnswer(i->{if(calls.incrementAndGet()==2)cancellation.cancel();return BigInteger.TWO;});
        assertThatThrownBy(()->cancellation.bound(()->observer.baseline(target.key(),1))).hasMessageContaining("CANCELLED_OR_DEADLINE");
        verify(deployments,never()).register(any());
    }
    @Test void baselineCannotResetOrAdoptHistoricalTarget()throws Exception{
        assertThatThrownBy(()->observer.baseline(target.key(),1)).hasMessage("AUDIT_BASELINE_ALREADY_FIXED");verifyNoInteractions(rpc);
        when(deployments.find(target)).thenReturn(Optional.empty());var orphan=new Transaction();orphan.setHash("history");when(rpc.auditTransactions(BigInteger.ONE)).thenReturn(List.of(orphan));
        assertThatThrownBy(()->observer.baseline(target.key(),1)).hasMessage("AUDIT_TARGET_HISTORY_EXISTS");verify(deployments,never()).register(any());
    }
    FuturesAuditDeployment freshLegacyTarget(){
        var legacy=new FuturesChainIdentity(chain.chainId(),"0x"+"88".repeat(20),chain.executor(),chain.priceSigner());
        return FuturesAuditDeployment.target(Route.LEGACY,"new-unused-legacy",legacy,CH,CH,FuturesLedger.POLICY_HASH);
    }
    FuturesAuthorizedAuditObserver lateLegacy(FuturesRpcGateway legacyRpc,boolean includeV2)throws Exception{
        var legacy=freshLegacyTarget();when(deployments.find(legacy)).thenReturn(Optional.empty());
        when(legacyRpc.height()).thenReturn(BigInteger.TWO);when(legacyRpc.block(BigInteger.TWO)).thenReturn(block(2,B2));
        when(legacyRpc.deploymentCodeHashes(any())).thenReturn(Map.of("controller",CH,"rules",CH));
        when(legacyRpc.auditLogs(any(),any())).thenReturn(List.of());when(legacyRpc.auditTransactions(any())).thenReturn(List.of());
        var contexts=new ArrayList<FuturesAuthorizedAuditObserver.Context>();contexts.add(new FuturesAuthorizedAuditObserver.Context(legacy,legacyRpc));
        if(includeV2)contexts.add(new FuturesAuthorizedAuditObserver.Context(target,rpc));
        return new FuturesAuthorizedAuditObserver(store,deployments,contexts);
    }
    @Test void freshLegacyBaselineAllowsKnownV2PositionsButProvesItsOwnGenesisHistoryEmpty()throws Exception{
        installOpen(1);var legacyRpc=mock(FuturesRpcGateway.class);var other=lateLegacy(legacyRpc,true);
        var result=other.baseline(freshLegacyTarget().key(),1);
        assertThat(result.deployment()).isEqualTo(freshLegacyTarget());assertThat(result.baseline().block()).isEqualTo(new Block(2,B2));
        verify(legacyRpc).auditLogs(BigInteger.ZERO,BigInteger.TWO);
        for(var n:List.of(BigInteger.ZERO,BigInteger.ONE,BigInteger.TWO))verify(legacyRpc).auditTransactions(n);
        verify(deployments).register(result);verify(deployments,never()).register(argThat(e->e.deployment().equals(target)));
        verify(store,never()).create(any());verify(store,never()).save(any());
    }
    @Test void lateLegacyBaselineStillRejectsAnyLegacyHistory()throws Exception{
        installOpen(1);var legacyRpc=mock(FuturesRpcGateway.class);var other=lateLegacy(legacyRpc,true);
        when(legacyRpc.auditTransactions(BigInteger.ONE)).thenReturn(List.of(new Transaction()));
        assertThatThrownBy(()->other.baseline(freshLegacyTarget().key(),1)).hasMessage("AUDIT_TARGET_HISTORY_EXISTS");
        verify(deployments,never()).register(any());
    }
    @Test void lateLegacyBaselineCannotIgnoreUnregisteredV2Positions()throws Exception{
        installOpen(1);var other=lateLegacy(mock(FuturesRpcGateway.class),false);
        assertThatThrownBy(()->other.baseline(freshLegacyTarget().key(),1)).hasMessage("FRESH_TARGET_LEDGER_REQUIRED");
        verify(deployments,never()).register(any());
    }
    @Test void completeRevisionZeroAndOneVerifyRawThreeEventsOwnerAndJournal()throws Exception{
        for(int revision=0;revision<=1;revision++){
            installOpen(revision);var r=observer.observe(1);
            assertThat(r.status()).as("revision %d: %s",revision,r).isEqualTo("MATCH");assertThat(r.complete()).isTrue();assertThat(r.targets().get(0).coverage().verifiedTransactions()).isEqualTo(1);assertThat(r.targets().get(0).coverage().verifiedEvents()).isEqualTo(3);
        }
        verify(rpc,never()).broadcast(any());verify(rpc,never()).estimate(any(),any(),any(),any(),any());verify(rpc,never()).pendingNonce(any());
    }
    @Test void wrongControllerEventOrChainOwnerIsMismatch()throws Exception{
        var receipt=installOpen(1);receipt.getLogs().get(2).setAddress(chain.rules());assertThat(observer.observe(1).status()).isEqualTo("MISMATCH");
        installOpen(1);when(rpc.positionOwner(anyString(),any())).thenReturn(chain.priceSigner());assertThat(observer.observe(1).status()).isEqualTo("MISMATCH");
    }
    @Test void duplicateReverseLogCannotBecomeCompleteAfterSetDeduplication()throws Exception{
        var receipt=installOpen(1);var repeated=new ArrayList<>(receipt.getLogs());repeated.add(receipt.getLogs().get(0));when(rpc.auditLogs(any(),any())).thenReturn(repeated);
        var r=observer.observe(1);assertThat(r.status()).isEqualTo("INCONCLUSIVE");assertThat(r.complete()).isFalse();assertThat(r.reason()).isEqualTo("DEPENDENCY_OR_HISTORICAL_EVIDENCE_UNAVAILABLE");
    }
    @Test void duplicateReverseTransactionCannotBecomeCompleteAfterSetDeduplication()throws Exception{
        var receipt=installOpen(1);var tx=rpc.transaction(receipt.getTransactionHash()).orElseThrow();when(rpc.auditTransactions(BigInteger.TWO)).thenReturn(List.of(tx,tx));
        var r=observer.observe(1);assertThat(r.status()).isEqualTo("INCONCLUSIVE");assertThat(r.complete()).isFalse();
    }
    @Test void duplicateCancellationEvidenceIsNotSilentlyExcludedFromCoverage()throws Exception{
        var log=new Log();log.setAddress(chain.engine());log.setTransactionHash("0x"+"c1".repeat(32));log.setBlockNumber("0x2");log.setBlockHash(B2);log.setTopics(List.of(Hash.sha3String("UserNonceCancelledV2(address,uint256)")));when(rpc.auditLogs(any(),any())).thenReturn(List.of(log,log));
        var r=observer.observe(1);assertThat(r.status()).isEqualTo("INCONCLUSIVE");assertThat(r.complete()).isFalse();assertThat(r.reason()).isEqualTo("DEPENDENCY_OR_HISTORICAL_EVIDENCE_UNAVAILABLE");
    }
    @Test void baselineRejectsPolicyMismatchBeforeRegistration(){
        when(deployments.find(target)).thenReturn(Optional.empty());var s=FuturesAuditAccountingTest.empty();setCut(new FuturesAuditSnapshot("0x"+"f1".repeat(32),s.initial(),s.house(),s.reserved(),List.of(),List.of(),List.of(),List.of(),List.of()));
        assertThatThrownBy(()->observer.baseline(target.key(),1)).hasMessage("AUDIT_BASELINE_POLICY_CONFLICT");verify(deployments,never()).register(any());
    }
    @Test void changedCutSuppressesProvisionalMismatchItems()throws Exception{
        var receipt=installOpen(1);receipt.getLogs().get(2).setAddress(chain.rules());when(store.fingerprint(any())).thenReturn("first","second");
        var r=observer.observe(1);assertThat(r.status()).isEqualTo("INCONCLUSIVE");assertThat(r.reason()).isEqualTo("CUT_CHANGED");assertThat(r.targets().get(0).items()).allMatch(i->i.verdict().equals("INCONCLUSIVE"));
    }
    @Test void outOfRangeAndUnregisteredPositionNeverBecomeComplete()throws Exception{
        when(rpc.height()).thenReturn(BigInteger.valueOf(300));when(rpc.block(BigInteger.valueOf(300))).thenReturn(block(300,B2));assertThat(observer.observe(1).reason()).isEqualTo("BOUNDED_READ_LIMIT");
        when(rpc.height()).thenReturn(BigInteger.TWO);var s=FuturesAuditAccountingTest.empty();setCut(new FuturesAuditSnapshot(s.policy(),s.initial(),s.house(),s.reserved(),List.of(FuturesAuditAccountingTest.position("OPEN_FAILED",0,null)),List.of(),List.of(),List.of(),List.of()));assertThat(observer.observe(1).reason()).isEqualTo("UNREGISTERED_OR_UNROUTABLE_DEPLOYMENT");
    }
    List<Transaction> reverseTransactions(int count,int offset){
        var out=new ArrayList<Transaction>();
        for(int n=0;n<count;n++){var tx=new Transaction();tx.setHash("0x"+String.format("%064x",offset+n+1));tx.setBlockHash(B2);tx.setBlockNumber("0x2");out.add(tx);}
        return out;
    }
    List<Log> cancellationLogs(List<Transaction> transactions){
        var logs=new ArrayList<Log>();
        for(int n=0;n<transactions.size();n++){
            var log=new Log();log.setAddress(chain.engine());log.setTransactionHash(transactions.get(n).getHash());
            log.setBlockNumber("0x2");log.setBlockHash(B2);log.setTransactionIndex(Numeric.encodeQuantity(BigInteger.valueOf(n)));log.setLogIndex(Numeric.encodeQuantity(BigInteger.valueOf(n)));
            log.setTopics(List.of(Hash.sha3String("UserNonceCancelledV2(address,uint256)"),"0x"+"0".repeat(24)+"11".repeat(20)));log.setData("0x"+"0".repeat(64));logs.add(log);
        }
        return logs;
    }
    @Test void fiftyOneExternalTransactionsExceedWholeObservationBudget()throws Exception{
        when(rpc.auditTransactions(BigInteger.TWO)).thenReturn(reverseTransactions(51,0));
        var r=observer.observe(1);assertThat(r.status()).isEqualTo("INCONCLUSIVE");assertThat(r.reason()).isEqualTo("BOUNDED_READ_LIMIT");assertThat(r.complete()).isFalse();
    }
    @Test void fiftyExternalTransactionsStayWithinBudgetButAreNotLedgerMatches()throws Exception{
        when(rpc.auditTransactions(BigInteger.TWO)).thenReturn(reverseTransactions(50,0));
        var r=observer.observe(1);assertThat(r.status()).isEqualTo("MISMATCH");assertThat(r.complete()).isTrue();
    }
    @Test void cancellationEventsAndReverseRowsShareOneDistinctTransactionBudget()throws Exception{
        var transactions=reverseTransactions(50,0);when(rpc.auditTransactions(BigInteger.TWO)).thenReturn(transactions);when(rpc.auditLogs(any(),any())).thenReturn(cancellationLogs(transactions));
        var r=observer.observe(1);assertThat(r.reason()).isEqualTo("CANCEL_COVERAGE_REQUIRED");assertThat(r.complete()).isFalse();
        when(rpc.auditLogs(any(),any())).thenReturn(cancellationLogs(reverseTransactions(51,0)));
        r=observer.observe(1);assertThat(r.reason()).isEqualTo("BOUNDED_READ_LIMIT");assertThat(r.complete()).isFalse();
    }
    @Test void distinctTransactionBudgetIsSharedAcrossRegisteredTargets()throws Exception{
        var legacyRpc=mock(FuturesRpcGateway.class);var other=lateLegacy(legacyRpc,true);var legacy=freshLegacyTarget();
        var legacyBase=new Baseline("legacy-base",legacy.executionId(),legacy.chain(),CH,legacy.policyHash(),FuturesLedger.INITIAL_CAPITAL.toString(),new Block(1,B1),Instant.EPOCH,"db",1);
        when(deployments.find(legacy)).thenReturn(Optional.of(new Envelope(legacy,legacyBase)));when(legacyRpc.block(BigInteger.ONE)).thenReturn(block(1,B1));
        when(legacyRpc.auditTransactions(BigInteger.TWO)).thenReturn(reverseTransactions(26,0));when(rpc.auditTransactions(BigInteger.TWO)).thenReturn(reverseTransactions(25,26));
        var r=other.observe(1);assertThat(r.status()).isEqualTo("INCONCLUSIVE");assertThat(r.reason()).isEqualTo("BOUNDED_READ_LIMIT");assertThat(r.complete()).isFalse();
        assertThat(r.targets()).allMatch(t->!t.coverage().complete());
    }
    @Test void invalidApprovalOrUnroutableDatabaseTransactionStillConsumesUnionBudget()throws Exception{
        for(boolean unroutable:new boolean[]{false,true}){
            installOpen(1);var snapshot=cut.futures();var evidence=snapshot.transactions().getFirst();var s=evidence.stored();
            var damaged=new FuturesTransactionStore.Stored(s.operationId(),"0x00",s.hash(),s.nonce(),s.status(),s.chainId(),s.engine(),s.sender(),s.report(),s.signature(),s.authorization());
            setCut(new FuturesAuditSnapshot(snapshot.policy(),snapshot.initial(),snapshot.house(),snapshot.reserved(),snapshot.positions(),snapshot.operations(),
                    List.of(new FuturesAuditSnapshot.Tx(damaged,evidence.block(),evidence.blockHash())),snapshot.journal(),snapshot.guards(),snapshot.authorizations(),unroutable?Map.of():snapshot.positionAuthorizations()));
            when(rpc.auditLogs(any(),any())).thenReturn(List.of());when(rpc.auditTransactions(BigInteger.TWO)).thenReturn(reverseTransactions(50,0));
            var r=observer.observe(1);assertThat(r.status()).isEqualTo("INCONCLUSIVE");assertThat(r.reason()).isEqualTo("BOUNDED_READ_LIMIT");assertThat(r.complete()).isFalse();
        }
    }
    @Test void databaseReceiptLogsAndReverseScanCountOneSharedTransactionAtFiftyBoundary()throws Exception{
        var receipt=installOpen(1);var reverse=reverseTransactions(49,0);reverse.add(rpc.transaction(receipt.getTransactionHash()).orElseThrow());
        when(rpc.auditTransactions(BigInteger.TWO)).thenReturn(reverse);
        var r=observer.observe(1);assertThat(r.status()).isEqualTo("MISMATCH");assertThat(r.complete()).isTrue();
        assertThat(r.reason()).isEqualTo("LEDGER_CHAIN_OR_COVERAGE_CONFLICT");
    }
    TransactionReceipt installOpen(int revision)throws Exception{
        var f=FuturesAuthorizedAuditVerifierTest.v2(revision);var s=f.stored();var r=s.report();var p=f.position();
        var journals=new ArrayList<FuturesAuditSnapshot.Journal>();
        journals.add(new FuturesAuditSnapshot.Journal("reserve",s.operationId(),p.id(),1,"RESERVE",BigInteger.ZERO,p.collateral(),BigInteger.ZERO,BigInteger.ZERO));
        if(revision==1)journals.add(new FuturesAuditSnapshot.Journal("reprice",s.operationId(),p.id(),1,"REPRICE",BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO));
        journals.add(new FuturesAuditSnapshot.Journal("confirm",s.operationId(),p.id(),1,"CONFIRM",BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO));
        var snapshot=new FuturesAuditSnapshot(FuturesLedger.POLICY_HASH,FuturesLedger.INITIAL_CAPITAL,FuturesLedger.INITIAL_CAPITAL,BigInteger.ZERO,List.of(p),List.of(f.operation()),List.of(new FuturesAuditSnapshot.Tx(s,2L,B2)),journals,List.of(new FuturesAuditSnapshot.Guard(1,p.accountRef(),p.id())),Map.of(s.operationId(),f.evidence()),Map.of(p.id(),f.evidence().positionAuthorization()));
        var amount=p.collateral().multiply(BigInteger.TEN);cut=new FuturesAuditStore.Cut(snapshot,new DbCut(Instant.EPOCH,List.of(new Balance(1,"mKRW",amount,p.collateral())),List.of(),List.of(),List.of(),List.of(new Grant("g",1,amount,Instant.EPOCH)),FuturesAuditAccounting.impact(snapshot,json)));when(store.authorizedCut()).thenReturn(cut);
        var applied=log(s,FuturesReceiptVerifier.APPLIED,List.of(r.operationId(),r.positionId()),encode(BigInteger.ZERO,BigInteger.ONE,r.priceE8(),p.collateral(),BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO),0,chain.rules());
        var consumed=log(s,FuturesReceiptVerifier.CONSUMED,List.of(r.operationId()),encode(r.observedAt(),r.validUntil()),1,chain.rules());
        String intent=Numeric.toHexString(com.pricetrack.exchange.futures.authorization.UserIntentV2Codec.digest(chain,s.authorization().intentV2()));
        var approved=log(s,FuturesReceiptVerifier.USER_EXECUTED_V2,List.of(intent,"0x"+"0".repeat(24)+s.authorization().owner().substring(2),r.positionId()),encode(Numeric.toBigInt(r.operationId()),Numeric.toBigInt(Numeric.toHexString(FuturesReportCodec.digest(r,chain.chainId(),chain.rules()))),BigInteger.ZERO,s.authorization().intentV2().nonce()),2,chain.engine());
        var receipt=new TransactionReceipt();receipt.setTransactionHash(s.hash());receipt.setBlockHash(B2);receipt.setBlockNumber("0x2");receipt.setTransactionIndex("0x0");receipt.setFrom(s.sender());receipt.setTo(s.engine());receipt.setStatus("0x1");receipt.setLogs(List.of(applied,consumed,approved));
        var tx=new Transaction();tx.setHash(s.hash());tx.setBlockHash(B2);tx.setBlockNumber("0x2");tx.setTransactionIndex("0x0");tx.setFrom(s.sender());tx.setTo(s.engine());tx.setNonce("0x0");tx.setValue("0x0");tx.setInput(UserIntentCodec.encode(s.report(),s.signature(),s.authorization()));
        when(rpc.receipt(s.hash())).thenReturn(Optional.of(receipt));when(rpc.transaction(s.hash())).thenReturn(Optional.of(tx));when(rpc.auditTransactions(BigInteger.TWO)).thenReturn(List.of(tx));when(rpc.auditLogs(any(),any())).thenReturn(receipt.getLogs());
        when(rpc.position(p.id(),BigInteger.ONE)).thenReturn(encode(new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO}));
        when(rpc.position(p.id(),BigInteger.TWO)).thenReturn(encode(Numeric.toBigInt(p.accountRef()),BigInteger.ZERO,BigInteger.TEN,p.quantity(),p.entryPrice(),p.collateral(),BigInteger.ONE,BigInteger.ONE,BigInteger.ZERO,BigInteger.ZERO,r.observedAt()));
        when(rpc.positionOwner(p.id(),BigInteger.TWO)).thenReturn(s.authorization().owner());
        when(rpc.userNonce(s.authorization().owner(),BigInteger.ONE)).thenReturn(s.authorization().intentV2().nonce());
        when(rpc.userNonce(s.authorization().owner(),BigInteger.TWO)).thenReturn(s.authorization().intentV2().nonce().add(BigInteger.ONE));return receipt;
    }
    FuturesAuditNonceCancellationVerifierTest.Case installCancellation(boolean typed,boolean successful)throws Exception{
        var c=FuturesAuditNonceCancellationVerifierTest.fixture(typed,successful,BigInteger.ZERO,FuturesAuditNonceCancellationVerifierTest.data(BigInteger.ZERO),chain.engine());
        c.tx().setBlockHash(B2);c.tx().setBlockNumber("0x2");c.receipt().setBlockHash(B2);c.receipt().setBlockNumber("0x2");
        for(var l:c.receipt().getLogs()){l.setBlockHash(B2);l.setBlockNumber("0x2");}
        var header=block(2,B2);header.setTransactions(List.of(new EthBlock.TransactionHash(c.tx().getHash())));
        when(rpc.block(BigInteger.TWO)).thenReturn(header);when(rpc.auditTransactions(BigInteger.TWO)).thenReturn(List.of(c.tx()));
        when(rpc.receipt(c.tx().getHash())).thenReturn(Optional.of(c.receipt()));when(rpc.auditLogs(any(),any())).thenReturn(c.receipt().getLogs());
        when(rpc.eoa(c.tx().getFrom(),BigInteger.TWO)).thenReturn(true);
        when(rpc.userNonce(c.tx().getFrom(),BigInteger.ONE)).thenReturn(BigInteger.ZERO);
        when(rpc.userNonce(c.tx().getFrom(),BigInteger.TWO)).thenReturn(successful?BigInteger.ONE:BigInteger.ZERO);return c;
    }
    @Test void directSignedTypeZeroAndTypeTwoCancellationAreCoveredWithoutFinancialWrites()throws Exception{
        for(boolean typed:new boolean[]{false,true}){
            installCancellation(typed,true);var result=observer.observe(1);assertThat(result.status()).isEqualTo("MATCH");assertThat(result.complete()).isTrue();
            assertThat(result.targets().getFirst().coverage().verifiedTransactions()).isZero();assertThat(result.targets().getFirst().coverage().verifiedEvents()).isZero();
            assertThat(result.targets().getFirst().cancellationCoverage()).isEqualTo(new FuturesAuthorizedAuditObserver.CancellationCoverage(1,1,0,1,true));
        }
        verify(store,never()).save(any());verify(rpc,never()).broadcast(any());verify(rpc,never()).estimate(any(),any(),any(),any(),any());
    }
    @Test void canonicalFailedCancellationWithoutEventsDoesNotConsumeUserNonce()throws Exception{
        for(boolean typed:new boolean[]{false,true}){installCancellation(typed,false);var result=observer.observe(1);assertThat(result.status()).isEqualTo("MATCH");assertThat(result.complete()).isTrue();}
    }
    @Test void cancellationHistoricalNonceMismatchIsMismatchButMissingAnchorIsInconclusive()throws Exception{
        var c=installCancellation(true,true);when(rpc.userNonce(c.tx().getFrom(),BigInteger.TWO)).thenReturn(BigInteger.TWO);
        assertThat(observer.observe(1).status()).isEqualTo("MISMATCH");
        when(rpc.userNonce(c.tx().getFrom(),BigInteger.ONE)).thenThrow(new java.io.IOException("private-history"));
        var result=observer.observe(1);assertThat(result.status()).isEqualTo("INCONCLUSIVE");assertThat(json.writeValueAsString(result)).doesNotContain("private-history");
    }
    @Test void unsupportedCancellationTypeAndTrailingAbiStayInconclusiveNotOrphanMismatch()throws Exception{
        var c=installCancellation(true,false);c.tx().setType("0x1");assertThat(observer.observe(1).status()).isEqualTo("INCONCLUSIVE");
        c=installCancellation(true,false);c.tx().setInput(c.tx().getInput()+"00");assertThat(observer.observe(1).status()).isEqualTo("INCONCLUSIVE");
    }
    @Test void cancellationRequiresDirectEoaAndRemovedOrMissingReceiptIsInconclusive()throws Exception{
        var c=installCancellation(true,true);when(rpc.eoa(c.tx().getFrom(),BigInteger.TWO)).thenReturn(false);assertThat(observer.observe(1).status()).isEqualTo("INCONCLUSIVE");
        c=installCancellation(true,true);c.receipt().getLogs().getFirst().setRemoved(true);assertThat(observer.observe(1).status()).isEqualTo("INCONCLUSIVE");
        c=installCancellation(true,false);when(rpc.receipt(c.tx().getHash())).thenReturn(Optional.empty());assertThat(observer.observe(1).status()).isEqualTo("INCONCLUSIVE");
    }
    @Test void cancellationContradictionIsMismatchAndMovingCutInvalidatesCancellationCompleteness()throws Exception{
        var c=installCancellation(true,true);c.tx().setFrom(chain.priceSigner());assertThat(observer.observe(1).status()).isEqualTo("MISMATCH");
        installCancellation(true,true);when(store.fingerprint(any())).thenReturn("before","after");var result=observer.observe(1);
        assertThat(result.status()).isEqualTo("INCONCLUSIVE");assertThat(result.targets().getFirst().cancellationCoverage().complete()).isFalse();
    }
    @Test void sameBlockIntentAndCancellationUseGlobalLogOrderAndDistinctHistoricalOwnerAnchors()throws Exception{
        var userReceipt=installOpen(1);var userTx=rpc.transaction(userReceipt.getTransactionHash()).orElseThrow();var c=installCancellation(true,true);
        c.tx().setTransactionIndex("0x1");c.receipt().setTransactionIndex("0x1");var event=c.receipt().getLogs().getFirst();event.setTransactionIndex("0x1");event.setLogIndex("0x3");
        var header=block(2,B2);header.setTransactions(List.of(new EthBlock.TransactionHash(userTx.getHash()),new EthBlock.TransactionHash(c.tx().getHash())));
        when(rpc.block(BigInteger.TWO)).thenReturn(header);when(rpc.auditTransactions(BigInteger.TWO)).thenReturn(List.of(userTx,c.tx()));
        var logs=new ArrayList<>(userReceipt.getLogs());logs.add(event);when(rpc.auditLogs(any(),any())).thenReturn(logs);
        var result=observer.observe(1);assertThat(result.status()).isEqualTo("MATCH");assertThat(result.targets().getFirst().coverage().verifiedTransactions()).isEqualTo(1);
        assertThat(result.targets().getFirst().cancellationCoverage().nonceGroups()).isEqualTo(2);
        event.setLogIndex("0x1");result=observer.observe(1);assertThat(result.status()).isEqualTo("INCONCLUSIVE");assertThat(result.complete()).isFalse();
    }
    Log log(FuturesTransactionStore.Stored s,String topic,List<String> indexed,String data,int index,String address){var l=new Log();var topics=new ArrayList<String>();topics.add(topic);topics.addAll(indexed);l.setTopics(topics);l.setData(data);l.setAddress(address);l.setTransactionHash(s.hash());l.setBlockHash(B2);l.setBlockNumber("0x2");l.setTransactionIndex("0x0");l.setLogIndex("0x"+index);return l;}
    String encode(BigInteger... values){var s=new StringBuilder("0x");for(var n:values)s.append(Numeric.toHexStringNoPrefixZeroPadded(n,64));return s.toString();}
}
