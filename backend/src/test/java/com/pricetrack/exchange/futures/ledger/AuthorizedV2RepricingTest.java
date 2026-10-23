package com.pricetrack.exchange.futures.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.authorization.*;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.runtime.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real H2 transactions/local signatures, fake RPC. Not PostgreSQL/Anvil acceptance. */
class AuthorizedV2RepricingTest {
    AuthorizedV2IntegrationTest v;AuthorizedV2RevisionStore revisions;AuthorizedV2Repricing repricer;
    static final FuturesChainIdentity C=AuthorizedV2IntegrationTest.C;
    @BeforeEach void setup()throws Exception{
        v=new AuthorizedV2IntegrationTest();v.setup();
        v.f.context.registerBean(AuthorizedV2RevisionStore.class,()->new AuthorizedV2RevisionStore(v.f.context.getBean(DataSource.class),v.f.ledger,v.f.store,v.f.context.getBean(ObjectMapper.class),v.f.market));
        revisions=v.f.context.getBean(AuthorizedV2RevisionStore.class);repricer=newRepricer();
    }
    @AfterEach void close(){v.close();}
    AuthorizedV2Repricing newRepricer(){return new AuthorizedV2Repricing(revisions,v.f.txs,v.f.market,C,AuthorizedFuturesStoreTest.PRICE,id->AuthorizedV2Config.recheckBeforeSigning(C,v.f.market,v.f.txs,v.f.rpc,id));}
    FuturesExecution execution(){return new FuturesExecution(v.f.ledger,new AuthorizedV2IntentStore(v.f.context.getBean(DataSource.class),v.f.ledger,v.store,C),v.f.market,C,AuthorizedFuturesStoreTest.PRICE,v.f.txs,v.sender,id->AuthorizedV2Config.recheckBeforeSigning(C,v.f.market,v.f.txs,v.f.rpc,id),repricer::reprice);}
    void tickDuringEstimate(boolean twice)throws Exception{
        var count=new AtomicInteger();when(v.f.rpc.estimate(anyString(),any(),any(),anyString(),anyString())).thenAnswer(x->{int n=count.incrementAndGet();if(n==1||twice)v.publish(n==1?"75001":"75002",1,true);return BigInteger.valueOf(500000);});
    }
    long count(String table){return v.f.jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    @Test void unchangedPriceUsesRevisionZero() {var op=v.accepted();execution().submitPrepared(op.id());assertThat(v.f.txs.executionRevision(op.id())).isZero();assertThat(count("futures_v2_execution_revisions")).isEqualTo(1);assertThat(v.f.txs.find(op.id())).isPresent();}
    @Test void oneFreshTickRepricesOpenAndCanonicalSettlementUsesNewQuantity()throws Exception{
        var op=v.accepted();var old=v.f.txs.fixedAuthorizedReport(op.id()).orElseThrow();var auth=v.f.txs.authorization(op.id());tickDuringEstimate(false);execution().submitPrepared(op.id());
        var stored=v.f.txs.find(op.id()).orElseThrow();assertThat(v.f.txs.executionRevision(op.id())).isEqualTo(1);assertThat(count("futures_v2_execution_revisions")).isEqualTo(2);
        assertThat(stored.report().priceE8()).isEqualTo(new BigInteger("7500100000000"));assertThat(stored.report().quantity()).isEqualTo(FuturesMath.quantity(FuturesLedgerTest.C,10,stored.report().priceE8()));
        assertThat(stored.authorization()).isEqualTo(auth);assertThat(v.f.store.fixedReport(op.id()).orElseThrow()).isEqualTo(old);
        assertThat(v.f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isEqualByComparingTo("100000");
        assertThat(v.receipt(op,stored).reconcile(op.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);
        assertThat(v.f.ledger.position(op.positionId(),1).quantity()).isEqualTo(stored.report().quantity());
    }
    @Test void secondTickRejectsWithoutAnotherRevisionAndReleasesOnlyOnce()throws Exception{
        var op=v.accepted();tickDuringEstimate(true);assertThatThrownBy(()->execution().submitPrepared(op.id())).hasMessage("PRICE_CHANGED");
        assertThat(v.f.ledger.operation(op.id()).state()).isEqualTo("REJECTED");assertThat(count("futures_v2_execution_revisions")).isEqualTo(2);assertThat(v.f.txs.find(op.id())).isEmpty();
        assertThat(v.f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isZero();
        execution().submitPrepared(op.id());assertThat(v.f.jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE entry_type='RELEASE'",Long.class)).isEqualTo(1);
    }
    @Test void priceAndQuantityBoundsNeverExpand()throws Exception{
        var op=v.accepted();v.publish("77000",1,true);assertThatThrownBy(()->execution().submitPrepared(op.id())).hasMessage("EXECUTION_OUTSIDE_USER_BOUNDS");assertThat(v.f.txs.executionRevision(op.id())).isZero();assertThat(v.f.txs.find(op.id())).isEmpty();
    }
    @Test void quantityOutsideSignedNarrowRangeRejects(){
        var r=v.request();var q=FuturesMath.quantity(r.margin(),r.leverage(),FuturesLedgerTest.ENTRY);String key=v.f.key();var draft=v.service.draft(1,key,new AuthorizedV2Service.Request(r.action(),null,0,r.side(),r.leverage(),r.margin(),r.priceMinE8(),r.priceMaxE8(),q,q,120));
        var op=v.service.submit(1,draft.id(),key,v.signature(draft));v.publish("75001",1,true);assertThatThrownBy(()->execution().submitPrepared(op.id())).hasMessage("EXECUTION_OUTSIDE_USER_BOUNDS");assertThat(v.f.txs.executionRevision(op.id())).isZero();
    }
    @Test void staleGenerationDeadlineAndNonceDoNotGetRefreshed(){
        var op=v.accepted();v.f.now.set(Instant.ofEpochSecond(1006));v.publish("75001",1,true);assertThatThrownBy(()->execution().submitPrepared(op.id())).hasMessage("MARKET_UNAVAILABLE");assertThat(v.f.txs.executionRevision(op.id())).isZero();
    }
    @Test void cancellationAtRefreshRejectsUnsigned(){
        var op=v.accepted();v.publish("75001",1,true);v.f.nonce.set(1);assertThatThrownBy(()->execution().submitPrepared(op.id())).hasMessage("USER_NONCE_OR_EOA_CHANGED");assertThat(v.f.txs.executionRevision(op.id())).isZero();assertThat(v.f.txs.find(op.id())).isEmpty();
    }
    @Test void revisionCommitFailureRollsBackFinancialAndEvidenceChanges(){
        var op=v.accepted();var old=v.f.txs.fixedAuthorizedReport(op.id()).orElseThrow();var mark=v.f.market.capture();
        v.f.jdbc.execute("ALTER TABLE futures_v2_execution_heads ADD CONSTRAINT inject_revision_failure CHECK(active_revision=0)");v.publish("75001",1,true);
        assertThatThrownBy(()->repricer.reprice(op.id(),old.getKey(),mark)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(v.f.txs.fixedAuthorizedReport(op.id()).orElseThrow()).isEqualTo(old);assertThat(v.f.ledger.operation(op.id()).price()).isEqualTo(old.getKey().priceE8());assertThat(count("futures_v2_execution_revisions")).isEqualTo(1);assertThat(count("futures_journal")).isEqualTo(1);
        assertThat(v.f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isEqualByComparingTo("100000");
    }
    @Test void twoWorkersOnlyAppendOnceAndRestartCannotResetAllowance()throws Exception{
        var op=v.accepted();var old=v.f.txs.fixedAuthorizedReport(op.id()).orElseThrow().getKey();var mark=v.f.market.capture();v.publish("75001",1,true);var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);
        Callable<Boolean> task=()->{go.await();try{return newRepricer().reprice(op.id(),old,mark);}catch(FuturesLedger.Failure x){assertThat(x.code).isEqualTo("REVISION_CONFLICT");return false;}};
        try{var a=pool.submit(task);var b=pool.submit(task);go.countDown();assertThat((a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0)).isEqualTo(1);}finally{pool.shutdownNow();}
        assertThat(count("futures_v2_execution_revisions")).isEqualTo(2);assertThat(newRepricer().reprice(op.id(),old,mark)).isFalse();
        assertThatThrownBy(()->v.sender.submit(op.id(),old,v.f.store.fixedReport(op.id()).orElseThrow().getValue())).isInstanceOf(FuturesLedger.Failure.class);assertThat(v.f.txs.find(op.id())).isEmpty();
    }
    @Test void signedResponseLossNeverRepricesAndKeepsSameRaw()throws Exception{
        var op=v.accepted();tickDuringEstimate(false);execution().submitPrepared(op.id());var stored=v.f.txs.find(op.id()).orElseThrow();
        v.f.now.set(Instant.ofEpochSecond(1200));v.publish("70000",2,false);execution().submitPrepared(op.id());var same=v.f.txs.find(op.id()).orElseThrow();
        assertThat(same.raw()).isEqualTo(stored.raw());assertThat(same.hash()).isEqualTo(stored.hash());assertThat(same.nonce()).isEqualTo(stored.nonce());assertThat(count("futures_v2_execution_revisions")).isEqualTo(2);
    }
    @Test void rawComputedBeforeRevisionCannotPassFinalDurableSignedFence(){
        var op=v.accepted();var old=v.f.txs.fixedAuthorizedReport(op.id()).orElseThrow();var a=v.f.txs.authorization(op.id());
        String data=UserIntentCodec.encode(old.getKey(),old.getValue(),a);
        byte[] raw=org.web3j.crypto.TransactionEncoder.signMessage(org.web3j.crypto.RawTransaction.createTransaction(BigInteger.ZERO,BigInteger.ONE,BigInteger.valueOf(500000),C.engine(),BigInteger.ZERO,data),31337,AuthorizedV2IntegrationTest.EXEC);
        String hex=org.web3j.utils.Numeric.toHexString(raw),hash=org.web3j.utils.Numeric.toHexString(org.web3j.crypto.Hash.sha3(raw));
        var mark=v.f.market.capture();v.publish("75001",1,true);assertThat(repricer.reprice(op.id(),old.getKey(),mark)).isTrue();
        assertThatThrownBy(()->v.f.txs.saveSigned(op.id(),C,old.getKey(),old.getValue(),hex,hash,0)).hasMessage("REVISION_CONFLICT");
        assertThat(v.f.txs.find(op.id())).isEmpty();assertThat(v.f.txs.executionRevision(op.id())).isEqualTo(1);assertThat(v.f.ledger.operation(op.id()).state()).isEqualTo("PREPARED");
        assertThat(v.f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isEqualByComparingTo("100000");
    }
    @Test void durableSignedFirstBlocksEvenDirectRevisionStoreAndPreservesRaw(){
        var op=v.accepted();execution().submitPrepared(op.id());var stored=v.f.txs.find(op.id()).orElseThrow();var old=stored.report();v.publish("75001",1,true);var mark=v.f.market.capture();
        var q=FuturesMath.quantity(old.collateralAfter(),old.leverage(),mark.price());
        var next=new FuturesReport(old.operationId(),old.positionId(),old.accountRef(),old.symbolHash(),old.action(),old.expectedVersion(),old.expectedCallEpoch(),old.side(),old.leverage(),q,mark.price(),old.collateralBefore(),old.collateralAfter(),mark.price(),old.observedAt(),old.validUntil(),old.policyHash(),old.executor());
        String ps=FuturesReportCodec.sign(next,C.chainId(),C.rules(),AuthorizedFuturesStoreTest.PRICE.getEcKeyPair());
        assertThatThrownBy(()->revisions.replaceUnsigned(op.id(),old,next,ps,mark)).hasMessage("REVISION_CONFLICT");
        var same=v.f.txs.find(op.id()).orElseThrow();assertThat(same.raw()).isEqualTo(stored.raw());assertThat(same.hash()).isEqualTo(stored.hash());assertThat(same.nonce()).isEqualTo(stored.nonce());
        assertThat(v.f.txs.executionRevision(op.id())).isZero();assertThat(count("futures_v2_execution_revisions")).isEqualTo(1);assertThat(count("futures_journal")).isEqualTo(1);
    }
    @Test void topupAndCloseRecomputeHouseWithoutDoubleCollateralLock()throws Exception{
        var open=v.accepted();var s=v.signed(open);assertThat(v.receipt(open,s).reconcile(open.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);v.f.nonce.incrementAndGet();
        for(Action action:java.util.List.of(Action.TOP_UP,Action.CLOSE)){
            var p=v.f.ledger.position(open.positionId(),1);var r=v.request();String key=v.f.key();var d=v.service.draft(1,key,new AuthorizedV2Service.Request(action,p.id(),p.version(),p.side(),p.leverage(),action==Action.TOP_UP?BigInteger.TEN.pow(22):BigInteger.ZERO,r.priceMinE8(),r.priceMaxE8(),p.quantity(),p.quantity(),120));
            var op=v.service.submit(1,d.id(),key,v.signature(d));v.publish(action==Action.TOP_UP?"75001":"75002",1,true);execution().submitPrepared(op.id());s=v.f.txs.find(op.id()).orElseThrow();
            assertThat(v.f.txs.executionRevision(op.id())).isEqualTo(1);var updated=v.f.ledger.operation(op.id());
            if(action==Action.CLOSE){assertThat(updated.reservedHouse()).isEqualTo(updated.expectedRealized().max(BigInteger.ZERO));assertThat(updated.reservedHouse()).isPositive();}
            assertThat(v.receipt(updated,s).reconcile(op.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);v.f.nonce.incrementAndGet();
        }
        assertThat(v.f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isZero();assertThat(v.f.jdbc.queryForObject("SELECT reserved FROM futures_house",BigDecimal.class)).isZero();
    }
    @Test void oldRevisionCannotBeSubstitutedForSignedSettlementEvidence()throws Exception{
        var op=v.accepted();var old=v.f.txs.fixedAuthorizedReport(op.id()).orElseThrow();tickDuringEstimate(false);execution().submitPrepared(op.id());
        var signed=v.f.txs.find(op.id()).orElseThrow();var reconciler=v.receipt(op,signed);
        // Deliberate corrupt ledger evidence, not a supported mutation API. Canonical tx remains revision1.
        var json=v.f.context.getBean(ObjectMapper.class);v.f.jdbc.update("UPDATE futures_operations SET report_json=?,signature=? WHERE id=?",json.writeValueAsString(old.getKey()),old.getValue(),op.id());
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.REVIEW_REQUIRED);
        assertThat(v.f.ledger.operation(op.id()).state()).isEqualTo("REVIEW_REQUIRED");assertThat(v.f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isEqualByComparingTo("100000");
    }
    @Test void insufficientHouseDuringCloseRevisionPreservesCollateralAndRejectsUnsigned()throws Exception{
        var open=v.accepted();var signed=v.signed(open);assertThat(v.receipt(open,signed).reconcile(open.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);v.f.nonce.incrementAndGet();
        var p=v.f.ledger.position(open.positionId(),1);var r=v.request();String key=v.f.key();
        var d=v.service.draft(1,key,new AuthorizedV2Service.Request(Action.CLOSE,p.id(),p.version(),p.side(),p.leverage(),BigInteger.ZERO,r.priceMinE8(),new BigInteger("10000000000000000"),p.quantity(),p.quantity(),120));
        var op=v.service.submit(1,d.id(),key,v.signature(d));var old=v.f.txs.fixedAuthorizedReport(op.id()).orElseThrow();v.publish("100000000",1,true);
        assertThatThrownBy(()->execution().submitPrepared(op.id())).hasMessage("POOL_INSUFFICIENT");assertThat(v.f.txs.executionRevision(op.id())).isZero();
        assertThat(v.f.txs.fixedAuthorizedReport(op.id()).orElseThrow()).isEqualTo(old);assertThat(v.f.ledger.operation(op.id()).state()).isEqualTo("REJECTED");assertThat(v.f.ledger.position(p.id(),1).status()).isEqualTo("OPEN");
        assertThat(v.f.jdbc.queryForObject("SELECT reserved FROM futures_house",BigDecimal.class)).isZero();assertThat(v.f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isEqualByComparingTo("100000");
    }
}
