package com.pricetrack.exchange.futures.ledger;

import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Direct orchestration + actual H2 atomic writes with mocked RPC, NOT PostgreSQL/Anvil acceptance. */
class FuturesReconcilerTest {
    AnnotationConfigApplicationContext context;FuturesLedger ledger;FuturesTransactionStore store;JdbcTemplate jdbc;
    FuturesRpcGateway rpc;FuturesTransactionSender sender;FuturesReconciler reconciler;
    Operation op;FuturesReport report;FuturesTransactionStore.Stored stored;
    TransactionReceipt receipt;Transaction tx;EthBlock.Block block;String state;
    final FuturesChainIdentity chain=FuturesTransactionSenderTest.CHAIN;
    @BeforeEach void setup()throws Exception {
        context=new AnnotationConfigApplicationContext(FuturesTransactionSenderTest.Fixture.class);
        ledger=context.getBean(FuturesLedger.class);store=context.getBean(FuturesTransactionStore.class);jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        ledger.initializeEmptyFixture();jdbc.update("INSERT INTO users VALUES(1)");jdbc.update("INSERT INTO user_balances VALUES(1,'mKRW',1000000,0)");
        op=ledger.prepareOpen(1,UUID.randomUUID().toString(),Side.LONG,FuturesLedgerTest.C,10,FuturesLedgerTest.ENTRY,1000);
        report=FuturesReport.from(op,ledger.position(op.positionId(),1),chain.executor());
        String signature=FuturesReportCodec.sign(report,chain.chainId(),chain.engine(),FuturesTransactionSenderTest.SIGNER.getEcKeyPair());
        rpc=mock(FuturesRpcGateway.class);
        when(rpc.pendingNonce(anyString())).thenReturn(BigInteger.ZERO);when(rpc.gasPrice()).thenReturn(BigInteger.ONE);
        when(rpc.estimate(anyString(),any(),any(),anyString(),anyString())).thenReturn(BigInteger.valueOf(500000));
        when(rpc.broadcast(anyString())).thenThrow(new IOException("INJECTED_RESPONSE_LOSS"));
        sender=new FuturesTransactionSender(chain,FuturesTransactionSenderTest.EXECUTOR,store,rpc);
        assertThat(sender.submit(op.id(),report,signature).status()).isEqualTo("SIGNED");stored=store.find(op.id()).orElseThrow();
        reconciler=new FuturesReconciler(chain,rpc,store,sender,ledger,1);
        String hash="0x"+"78".repeat(32);
        block=new EthBlock.Block();block.setNumber("0x7");block.setHash(hash);block.setTimestamp("0x3e8");
        tx=new Transaction();tx.setHash(stored.hash());tx.setBlockNumber("0x7");tx.setBlockHash(hash);tx.setTransactionIndex("0x0");
        tx.setNonce("0x0");tx.setFrom(chain.executor());tx.setTo(chain.engine());tx.setValue("0x0");tx.setInput(FuturesReportCodec.encode(report,signature));
        receipt=new TransactionReceipt();receipt.setTransactionHash(stored.hash());receipt.setBlockHash(hash);receipt.setBlockNumber("0x7");receipt.setTransactionIndex("0x0");
        receipt.setFrom(chain.executor());receipt.setTo(chain.engine());receipt.setStatus("0x1");
        var a=log(FuturesReceiptVerifier.APPLIED,List.of(report.operationId(),report.positionId()),encode(BigInteger.ZERO,BigInteger.ONE,report.priceE8(),report.collateralAfter(),BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO),0);
        var c=log(FuturesReceiptVerifier.CONSUMED,List.of(report.operationId()),encode(report.observedAt(),report.validUntil()),1);
        receipt.setLogs(List.of(a,c));
        state=encode(Numeric.toBigInt(report.accountRef()),BigInteger.ZERO,BigInteger.TEN,report.quantity(),report.entryPriceE8(),report.collateralAfter(),BigInteger.ONE,BigInteger.ONE,BigInteger.ZERO,BigInteger.ZERO,report.observedAt());
        when(rpc.receipt(stored.hash())).thenReturn(Optional.of(receipt));when(rpc.transaction(stored.hash())).thenReturn(Optional.of(tx));
        when(rpc.block(BigInteger.valueOf(7))).thenReturn(block);when(rpc.height()).thenReturn(BigInteger.valueOf(7));
        when(rpc.position(op.positionId(),BigInteger.valueOf(7))).thenReturn(state);
    }
    @AfterEach void stop() { context.close(); }
    Log log(String topic,List<String> indexed,String data,int index) {
        var l=new Log();var topics=new ArrayList<String>();topics.add(topic);topics.addAll(indexed);l.setTopics(topics);
        l.setAddress(chain.engine());l.setData(data);l.setTransactionHash(stored.hash());l.setBlockHash(block.getHash());l.setBlockNumber("0x7");l.setTransactionIndex("0x0");l.setLogIndex("0x"+index);return l;
    }
    String encode(BigInteger... values) { var s=new StringBuilder("0x");for(var v:values)s.append(Numeric.toHexStringNoPrefixZeroPadded(v,64));return s.toString(); }
    void held() {
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances",java.math.BigDecimal.class)).isEqualByComparingTo("100000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE entry_type<>'RESERVE'",Long.class)).isZero();
    }
    @Test void missingReceiptRecoversOnlySameSignedRawAndKeepsReservation()throws Exception {
        when(rpc.receipt(stored.hash())).thenReturn(Optional.empty());
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.WAITING);held();
        verify(rpc,times(2)).broadcast(stored.raw());verify(rpc,times(1)).pendingNonce(chain.executor());
        assertThat(store.find(op.id()).orElseThrow().hash()).isEqualTo(stored.hash());
    }
    @Test void rpcFailureAndMissingTransactionKeepLocks()throws Exception {
        when(rpc.receipt(stored.hash())).thenThrow(new IOException("SECRET_RPC_BODY"));
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.RPC_UNAVAILABLE);held();
        doReturn(Optional.of(receipt)).when(rpc).receipt(stored.hash());when(rpc.transaction(stored.hash())).thenReturn(Optional.empty());
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.RPC_UNAVAILABLE);held();
    }
    @Test void insufficientConfirmationsAndNonCanonicalReceiptAreWaiting()throws Exception {
        var two=new FuturesReconciler(chain,rpc,store,sender,ledger,2);
        assertThat(two.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.WAITING);held();
        var changed=new EthBlock.Block();changed.setHash("0x"+"ff".repeat(32));changed.setNumber("0x7");
        when(rpc.block(BigInteger.valueOf(7))).thenReturn(changed);
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.WAITING);held();
    }
    @Test void reorgBetweenFirstAndSecondCanonicalReadDoesNotSettle()throws Exception {
        var changed=new EthBlock.Block();changed.setHash("0x"+"ff".repeat(32));changed.setNumber("0x7");
        when(rpc.block(BigInteger.valueOf(7))).thenReturn(block,changed);
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.WAITING);held();
    }
    @Test void canonicalFailedReceiptReleasesOnlyOnce() {
        receipt.setStatus("0x0");receipt.setLogs(List.of());
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.REVERTED);
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.ALREADY_FINAL);
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances",java.math.BigDecimal.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE entry_type='REVERT_RELEASE'",Long.class)).isEqualTo(1);
    }
    @Test void conflictingEvidenceIsQuarantinedAndLateSubmittedCannotUndoReview() {
        receipt.getLogs().get(0).setBlockNumber("0x8");
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.REVIEW_REQUIRED);held();
        store.markSubmitted(op.id(),stored.hash());
        assertThat(store.find(op.id()).orElseThrow().status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(store.hasUnresolved(chain.executor())).isTrue();
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.ALREADY_FINAL);
    }
    @Test void concurrentSuccessHasExactlyOneSettlementJournal()throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var tasks=new ArrayList<Future<FuturesReconciler.Result>>();
            for(int i=0;i<2;i++) tasks.add(pool.submit(()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("barrier");return reconciler.reconcile(op.id());}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(var t:tasks)assertThat(t.get(10,TimeUnit.SECONDS)).isIn(FuturesReconciler.Result.CONFIRMED,FuturesReconciler.Result.ALREADY_FINAL);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE entry_type='CONFIRM'",Long.class)).isEqualTo(1);
            assertThat(ledger.operation(op.id()).state()).isEqualTo("CONFIRMED");
        } finally { start.countDown();pool.shutdownNow(); }
    }
}
