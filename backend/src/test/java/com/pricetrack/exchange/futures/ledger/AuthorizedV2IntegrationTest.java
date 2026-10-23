package com.pricetrack.exchange.futures.ledger;

import com.pricetrack.exchange.futures.authorization.*;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.Authorization;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.runtime.*;
import com.pricetrack.exchange.market.*;
import com.pricetrack.exchange.market.model.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.web3j.crypto.*;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual H2 transactions + real local signatures + mocked market/RPC. NOT PostgreSQL/Anvil. */
class AuthorizedV2IntegrationTest {
    static final Credentials EXEC=Credentials.create("6bf54");
    static final FuturesChainIdentity C=new FuturesChainIdentity(BigInteger.valueOf(31337),"0x"+"22".repeat(20),EXEC.getAddress(),AuthorizedFuturesStoreTest.PRICE.getAddress(),"0x"+"23".repeat(20),"2");
    AuthorizedFuturesStoreTest f;AuthorizedV2Store store;AuthorizedV2Service service;MarketPriceService prices;AtomicReference<TradingPriceContext> tick;FuturesTransactionSender sender;TransactionReceipt lastReceipt;
    @BeforeEach void setup()throws Exception{
        f=new AuthorizedFuturesStoreTest();f.setup();f.link(1,AuthorizedFuturesStoreTest.USER);
        f.context.registerBean(AuthorizedV2Store.class,()->new AuthorizedV2Store(f.context.getBean(javax.sql.DataSource.class),f.ledger,f.store,f.context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)));store=f.context.getBean(AuthorizedV2Store.class);
        prices=mock(MarketPriceService.class);tick=new AtomicReference<>();when(prices.currentForTrading()).thenAnswer(x->tick.get());
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return f.now.get();}};f.market=new FuturesMarketGate(prices,clock);publish("75000",1,true);
        var access=new AuthorizedFuturesService.ChainAccess(){public void verify(){}public boolean eoa(String owner){return true;}public BigInteger nonce(String owner){return BigInteger.valueOf(f.nonce.get());}};
        service=new AuthorizedV2Service(store,f.store,f.ledger,f.market,C,AuthorizedFuturesStoreTest.PRICE,access,f.wake::incrementAndGet);
        when(f.rpc.eoa(anyString())).thenReturn(true);when(f.rpc.userNonce(anyString())).thenAnswer(x->BigInteger.valueOf(f.nonce.get()));sender=new FuturesTransactionSender(C,EXEC,f.txs,f.rpc);
    }
    @AfterEach void close(){f.close();}
    void publish(String price,long generation,boolean allowed){tick.set(new TradingPriceContext(new MarketPriceSnapshot("mSEC",new BigDecimal(price),new BigDecimal("75000"),BigDecimal.ZERO,BigDecimal.ZERO,MarketStatus.OPEN,PriceStatus.SIMULATED,"SIMULATED",f.now.get()),allowed,"controlled",generation));}
    AuthorizedV2Service.Request request(){return new AuthorizedV2Service.Request(Action.OPEN,null,0,Side.LONG,10,FuturesLedgerTest.C,new BigInteger("7400000000000"),new BigInteger("7600000000000"),new BigInteger("13157894736842105263"),new BigInteger("13513513513513513513"),120);}
    String signature(AuthorizedV2Service.DraftView d){return AuthorizedFuturesStoreTest.sig(UserIntentV2Codec.digest(C,store.draft(1,d.id(),false).intent()),AuthorizedFuturesStoreTest.USER);}
    Operation accepted(){String key=f.key();var d=service.draft(1,key,request());return service.submit(1,d.id(),key,signature(d));}
    FuturesExecution execution(){return new FuturesExecution(f.ledger,new AuthorizedV2IntentStore(f.context.getBean(javax.sql.DataSource.class),f.ledger,store,C),f.market,C,AuthorizedFuturesStoreTest.PRICE,f.txs,sender,id->AuthorizedV2Config.recheckBeforeSigning(C,f.market,f.txs,f.rpc,id));}
    FuturesTransactionStore.Stored signed(Operation op){execution().submitPrepared(op.id());return f.txs.find(op.id()).orElseThrow();}
    void emptyLedger(){assertThat(f.jdbc.queryForObject("SELECT count(*) FROM futures_operations",Long.class)).isZero();assertThat(f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isZero();}
    @Test void noDraftMarketReportThenTenSecondWaitUsesNewActualPriceAndGeneration(){
        String key=f.key();var d=service.draft(1,key,request());verifyNoInteractions(prices);emptyLedger();String signature=signature(d);
        f.now.set(Instant.ofEpochSecond(1010));publish("75500",7,true);var op=service.submit(1,d.id(),key,signature);
        var fixed=f.txs.fixedAuthorizedReport(op.id()).orElseThrow().getKey();assertThat(fixed.observedAt()).isEqualTo(BigInteger.valueOf(1010));assertThat(fixed.validUntil()).isEqualTo(BigInteger.valueOf(1040));assertThat(fixed.priceE8()).isEqualTo(new BigInteger("7550000000000"));
        assertThat(fixed.quantity()).isEqualTo(FuturesMath.quantity(FuturesLedgerTest.C,10,fixed.priceE8()));assertThat(f.store.operation(op.id()).intentV2().issuedAt()).isEqualTo(BigInteger.valueOf(1000));assertThat(f.store.operation(op.id()).userSignature()).isEqualTo(signature);
    }
    @Test void outsideUserPriceOrQuantityBoundsDoesNotReserve(){
        String key=f.key();var d=service.draft(1,key,request());publish("77000",1,true);assertThatThrownBy(()->service.submit(1,d.id(),key,signature(d))).hasMessage("EXECUTION_OUTSIDE_USER_BOUNDS");emptyLedger();
        publish("75000",1,true);assertThat(service.submit(1,d.id(),key,signature(d)).state()).isEqualTo("PREPARED");
    }
    @Test void executionQuantityMustBeInExplicitUserRange(){
        var r=request();String key=f.key();var d=service.draft(1,key,new AuthorizedV2Service.Request(r.action(),null,0,r.side(),r.leverage(),r.margin(),r.priceMinE8(),r.priceMaxE8(),new BigInteger("13400000000000000000"),r.quantityMax(),120));
        assertThatThrownBy(()->service.submit(1,d.id(),key,signature(d))).hasMessage("EXECUTION_OUTSIDE_USER_BOUNDS");emptyLedger();
    }
    @Test void userNonceExpiryScopeAndSemanticKeyConflictsRejectBeforeCapture(){
        String key=f.key();var d=service.draft(1,key,request());String signature=signature(d);
        assertThatThrownBy(()->service.submit(2,d.id(),key,signature)).hasMessage("AUTHORIZED_V2_DRAFT_NOT_FOUND");
        f.nonce.incrementAndGet();assertThatThrownBy(()->service.submit(1,d.id(),key,signature)).hasMessage("USER_NONCE_OR_EOA_CHANGED");f.nonce.set(0);
        f.now.set(Instant.ofEpochSecond(1121));assertThatThrownBy(()->service.submit(1,d.id(),key,signature)).hasMessage("USER_INTENT_EXPIRED_OR_FUTURE");verifyNoInteractions(prices);emptyLedger();
        var r=request();var changed=new AuthorizedV2Service.Request(r.action(),null,0,Side.SHORT,10,r.margin(),r.priceMinE8(),r.priceMaxE8(),r.quantityMin(),r.quantityMax(),120);assertThatThrownBy(()->service.draft(1,key,changed)).hasMessage("IDEMPOTENCY_CONFLICT");
    }
    @Test void concurrentSubmitAndRepeatedAcceptedRequestPinSingleReport()throws Exception{
        String key=f.key();var d=service.draft(1,key,request());String signature=signature(d);var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);
        try{var a=pool.submit(()->{go.await();return service.submit(1,d.id(),key,signature);});var b=pool.submit(()->{go.await();return service.submit(1,d.id(),key,signature);});go.countDown();assertThat(a.get(8,TimeUnit.SECONDS).id()).isEqualTo(b.get(8,TimeUnit.SECONDS).id());}finally{pool.shutdownNow();}
        assertThat(f.jdbc.queryForObject("SELECT count(*) FROM futures_operations",Long.class)).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT count(*) FROM futures_journal",Long.class)).isEqualTo(1);
        var fixed=f.txs.fixedAuthorizedReport(d.operationId()).orElseThrow();f.now.set(Instant.ofEpochSecond(1200));publish("70000",2,false);assertThat(service.submit(1,d.id(),key,signature).id()).isEqualTo(d.operationId());assertThat(f.txs.fixedAuthorizedReport(d.operationId()).orElseThrow()).isEqualTo(fixed);
    }
    @Test void inconsistentExecutionReportRollsBackAllReservationsAndMetadata(){
        String key=f.key();var d=service.draft(1,key,request());var i=store.draft(1,d.id(),false).intent();var mark=f.market.capture();
        var r=new FuturesReport(i.operationId(),i.positionId(),i.accountRef(),i.symbolHash(),0,i.expectedVersion(),i.expectedCallEpoch(),i.side(),i.leverage(),FuturesMath.quantity(i.collateralAfter(),10,mark.price()),mark.price(),i.collateralBefore(),i.collateralAfter(),mark.price(),BigInteger.valueOf(1000),BigInteger.valueOf(1031),i.policyHash(),C.engine());
        String ps=FuturesReportCodec.sign(r,C.chainId(),C.rules(),AuthorizedFuturesStoreTest.PRICE.getEcKeyPair());assertThatThrownBy(()->store.accept(1,d.id(),key,signature(d),r,ps,mark,f.now.get())).hasMessage("AUTHORIZED_REPORT_CONFLICT");emptyLedger();assertThat(f.jdbc.queryForObject("SELECT count(*) FROM futures_authorized_v2_positions",Long.class)).isZero();assertThat(store.draft(1,d.id(),false).state()).isEqualTo("DRAFT");
    }
    @Test void actualExecutionPriceChangeAfterEstimateRejectsUnsignedOnly()throws Exception{
        var op=accepted();when(f.rpc.estimate(anyString(),any(),any(),anyString(),anyString())).thenAnswer(x->{publish("75001",1,true);return BigInteger.valueOf(500000);});
        assertThatThrownBy(()->execution().submitPrepared(op.id())).hasMessage("PRICE_CHANGED");assertThat(f.txs.find(op.id())).isEmpty();assertThat(f.ledger.operation(op.id()).state()).isEqualTo("REJECTED");assertThat(f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isZero();verify(f.rpc,never()).broadcast(anyString());
    }
    @Test void generationChangeAndUserCancellationBeforeSigningBlock(){
        var op=accepted();publish("75000",2,true);assertThatThrownBy(()->execution().submitPrepared(op.id())).hasMessage("MARKET_CONNECTION_CHANGED");assertThat(f.txs.find(op.id())).isEmpty();
    }
    @Test void onchainNonceChangeBeforeSigningDoesNotAuthorizeReplacement(){
        var op=accepted();f.nonce.set(1);assertThatThrownBy(()->execution().submitPrepared(op.id())).hasMessage("USER_NONCE_OR_EOA_CHANGED");assertThat(f.ledger.operation(op.id()).state()).isEqualTo("REJECTED");assertThat(f.txs.find(op.id())).isEmpty();assertThat(service.nonce(1).get("verification")).isEqualTo("OBSERVED_LATEST_NOT_CANCELLATION_RECEIPT");
    }
    @Test void tickChangeDuringFinalNonceRpcStillBlocksBeforeRawSigning()throws Exception{
        var op=accepted();when(f.rpc.userNonce(anyString())).thenAnswer(x->{publish("75001",1,true);return BigInteger.ZERO;});
        assertThatThrownBy(()->execution().submitPrepared(op.id())).hasMessage("PRICE_CHANGED");assertThat(f.txs.find(op.id())).isEmpty();assertThat(f.ledger.operation(op.id()).state()).isEqualTo("REJECTED");verify(f.rpc,never()).broadcast(anyString());
    }
    @Test void shortUserDeadlineCanExpireWhileActualReportIsStillFresh(){
        var r=request();String key=f.key();var d=service.draft(1,key,new AuthorizedV2Service.Request(r.action(),null,0,r.side(),r.leverage(),r.margin(),r.priceMinE8(),r.priceMaxE8(),r.quantityMin(),r.quantityMax(),1));
        var op=service.submit(1,d.id(),key,signature(d));f.now.set(Instant.ofEpochSecond(1002));publish("75000",1,true);
        assertThatThrownBy(()->execution().submitPrepared(op.id())).hasMessage("USER_INTENT_EXPIRED_OR_FUTURE");assertThat(f.txs.find(op.id())).isEmpty();assertThat(f.ledger.operation(op.id()).state()).isEqualTo("REJECTED");
    }
    FuturesReconciler receipt(Operation op,FuturesTransactionStore.Stored s)throws Exception{
        var r=s.report();var p=f.ledger.position(op.positionId(),1);var m=FuturesMath.mark(p.side(),p.quantity(),p.entryPrice(),r.collateralAfter(),r.priceE8());boolean terminal=op.action()==Action.CLOSE||op.action()==Action.LIQUIDATE;
        boolean grace=p.riskStatus().equals("GRACE");long epoch=p.callEpoch(),deadline=p.deadline();if(op.action()==Action.START_CALL){grace=true;epoch++;deadline=r.observedAt().longValueExact()+60;}if(op.action()==Action.CLEAR_CALL||op.action()==Action.TOP_UP&&!m.atRisk()){grace=false;deadline=0;}
        var logs=new ArrayList<Log>();logs.add(f.log(C.rules(),s,FuturesReceiptVerifier.APPLIED,List.of(r.operationId(),r.positionId()),f.words(BigInteger.valueOf(r.action()),r.expectedVersion().add(BigInteger.ONE),r.priceE8(),r.collateralAfter(),m.unrealized(),terminal?m.realized():BigInteger.ZERO,terminal?m.badDebt():BigInteger.ZERO,BigInteger.valueOf(epoch),BigInteger.valueOf(deadline)),0));
        logs.add(f.log(C.rules(),s,FuturesReceiptVerifier.CONSUMED,List.of(r.operationId()),f.words(r.observedAt(),r.validUntil()),1));
        var a=s.authorization();if(UserIntentCodec.userAction(r.action()))logs.add(f.log(C.engine(),s,FuturesReceiptVerifier.USER_EXECUTED_V2,List.of(Numeric.toHexString(UserIntentV2Codec.digest(C,a.intentV2())),"0x"+"0".repeat(24)+a.owner().substring(2),r.positionId()),f.words(Numeric.toBigInt(r.operationId()),Numeric.toBigInt(Numeric.toHexString(FuturesReportCodec.digest(r,C.chainId(),C.rules()))),BigInteger.valueOf(r.action()),a.intentV2().nonce()),2));
        var first=logs.get(0);var receipt=new TransactionReceipt();receipt.setTransactionHash(s.hash());receipt.setFrom(C.executor());receipt.setTo(C.engine());receipt.setBlockHash(first.getBlockHash());receipt.setBlockNumber("0x7");receipt.setTransactionIndex("0x0");receipt.setStatus("0x1");receipt.setLogs(logs);lastReceipt=receipt;
        var tx=new Transaction();tx.setHash(s.hash());tx.setFrom(C.executor());tx.setTo(C.engine());tx.setNonce(Numeric.encodeQuantity(BigInteger.valueOf(s.nonce())));tx.setValue("0x0");tx.setInput(UserIntentCodec.encode(r,s.signature(),a));tx.setBlockHash(first.getBlockHash());tx.setBlockNumber("0x7");tx.setTransactionIndex("0x0");
        var block=new EthBlock.Block();block.setHash(first.getBlockHash());block.setNumber("0x7");block.setTimestamp(Numeric.encodeQuantity(r.observedAt()));
        when(f.rpc.receipt(s.hash())).thenReturn(Optional.of(receipt));when(f.rpc.transaction(s.hash())).thenReturn(Optional.of(tx));when(f.rpc.block(BigInteger.valueOf(7))).thenReturn(block);when(f.rpc.height()).thenReturn(BigInteger.valueOf(7));when(f.rpc.positionOwner(op.positionId(),BigInteger.valueOf(7))).thenReturn(a.owner());
        when(f.rpc.position(op.positionId(),BigInteger.valueOf(7))).thenReturn(f.words(Numeric.toBigInt(r.accountRef()),BigInteger.valueOf(r.side()),BigInteger.valueOf(r.leverage()),r.quantity(),r.entryPriceE8(),r.collateralAfter(),BigInteger.valueOf(terminal?(op.action()==Action.CLOSE?3:4):grace?2:1),r.expectedVersion().add(BigInteger.ONE),BigInteger.valueOf(epoch),BigInteger.valueOf(deadline),r.observedAt()));
        return new FuturesReconciler(C,f.rpc,f.txs,sender,f.ledger,1);
    }
    @Test void signedRawIsPreservedAcrossNonceChangeUntilCanonicalRevert()throws Exception{
        var op=accepted();var tx=signed(op);f.nonce.set(1);f.now.set(Instant.ofEpochSecond(1200));publish("70000",2,false);
        execution().submitPrepared(op.id());var same=f.txs.find(op.id()).orElseThrow();assertThat(same.raw()).isEqualTo(tx.raw());assertThat(same.hash()).isEqualTo(tx.hash());assertThat(same.nonce()).isEqualTo(tx.nonce());assertThat(same.status()).isEqualTo("SIGNED");assertThat(f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isEqualByComparingTo("100000");
        var reconcile=receipt(op,same);lastReceipt.setStatus("0x0");lastReceipt.setLogs(List.of());assertThat(reconcile.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.REVERTED);assertThat(f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isZero();
    }
    @Test void actualReceiptBeatsCurrentNonceAmbiguityAndSettlesOpenTopupClose()throws Exception{
        var op=accepted();var tx=signed(op);f.nonce.set(1);assertThat(receipt(op,tx).reconcile(op.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);
        for(Action action:List.of(Action.TOP_UP,Action.CLOSE)){var p=f.ledger.position(op.positionId(),1);String key=f.key();var r=request();var d=service.draft(1,key,new AuthorizedV2Service.Request(action,p.id(),p.version(),p.side(),p.leverage(),action==Action.TOP_UP?BigInteger.TEN.pow(22):BigInteger.ZERO,r.priceMinE8(),r.priceMaxE8(),p.quantity(),p.quantity(),120));op=service.submit(1,d.id(),key,signature(d));tx=signed(op);assertThat(receipt(op,tx).reconcile(op.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);f.nonce.incrementAndGet();}
        assertThat(f.jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isZero();assertThat(f.jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE entry_type='CONFIRM'",Long.class)).isEqualTo(3);
    }
    @Test void versionRoutingLegacyBypassAndAuditFailClosed(){
        String key=f.key();var d=service.draft(1,key,request());var op=service.submit(1,d.id(),key,signature(d));assertThat(new AuthorizedIntentStore(f.context.getBean(javax.sql.DataSource.class),f.ledger,f.store).prepared()).isEmpty();assertThat(new AuthorizedV2IntentStore(f.context.getBean(javax.sql.DataSource.class),f.ledger,store,C).prepared()).contains(op.id());
        assertThatThrownBy(()->f.service.submit(1,d.id(),key,"invalid")).hasMessage("AUTHORIZED_DRAFT_NOT_FOUND");
        assertThatThrownBy(()->f.ledger.prepare(1,op.positionId(),f.key(),Action.CLOSE,0,op.price(),op.observedAt(),BigInteger.ZERO)).hasMessage("FUTURES_CONTRACT_VERSION_CONFLICT");
        assertThatThrownBy(()->com.pricetrack.exchange.futures.audit.FuturesAuditSnapshot.read(f.jdbc,new com.fasterxml.jackson.databind.ObjectMapper())).isInstanceOf(com.pricetrack.exchange.futures.audit.FuturesAuditSnapshot.AuthorizedCoverageUnavailable.class);
    }
    @Test void v2AutomaticRiskUsesNoUserIntentAndSharesExistingFinancialRules()throws Exception{
        var op=accepted();var s=signed(op);assertThat(receipt(op,s).reconcile(op.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);
        publish("70000",1,true);var call=store.automatic(1,op.positionId(),f.key(),Action.START_CALL,1,f.market.capture(),C);s=signed(call);assertThat(s.authorization().intentV2()).isNull();assertThat(receipt(call,s).reconcile(call.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);
        f.now.set(Instant.ofEpochSecond(1060));publish("70000",1,true);f.nonce.set(99);var liq=store.automatic(1,op.positionId(),f.key(),Action.LIQUIDATE,2,f.market.capture(),C);s=signed(liq);assertThat(receipt(liq,s).reconcile(liq.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);assertThat(f.ledger.position(op.positionId(),1).status()).isEqualTo("LIQUIDATED");assertThat(new AuthorizedIntentStore(f.context.getBean(javax.sql.DataSource.class),f.ledger,f.store).prepared()).isEmpty();
    }
}
