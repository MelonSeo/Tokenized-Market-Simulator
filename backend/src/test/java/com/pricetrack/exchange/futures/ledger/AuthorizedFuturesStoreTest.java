package com.pricetrack.exchange.futures.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.authorization.*;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.*;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.runtime.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.web3j.crypto.*;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** H2/PostgreSQL syntax mode + fake RPC, real local signatures. No PostgreSQL/Anvil/network claim. */
class AuthorizedFuturesStoreTest {
    static final Credentials USER=Credentials.create("6f"),OTHER=Credentials.create("de"),PRICE=Credentials.create("14d"),EXEC=Credentials.create("6bf53");
    static final FuturesChainIdentity CHAIN=new FuturesChainIdentity(BigInteger.valueOf(31337),"0x"+"12".repeat(20),EXEC.getAddress(),PRICE.getAddress(),"0x"+"13".repeat(20));
    static final String ORIGIN="http://localhost:5173";
    @Configuration static class Fixture extends FuturesTransactionSenderTest.Fixture {
        @Bean AuthorizedFuturesStore authorized(DataSource ds,FuturesLedger ledger,ObjectMapper json){return new AuthorizedFuturesStore(ds,ledger,json);}
    }
    AnnotationConfigApplicationContext context;FuturesLedger ledger;FuturesTransactionStore txs;AuthorizedFuturesStore store;JdbcTemplate jdbc;
    FuturesMarketGate market;AuthorizedFuturesService service;AtomicReference<Instant> now;AtomicInteger wake,nonce;
    FuturesRpcGateway rpc;FuturesTransactionSender sender;
    @BeforeEach void setup()throws Exception {
        context=new AnnotationConfigApplicationContext(Fixture.class);ledger=context.getBean(FuturesLedger.class);txs=context.getBean(FuturesTransactionStore.class);store=context.getBean(AuthorizedFuturesStore.class);jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        ledger.initializeEmptyFixture();for(long id=1;id<=2;id++){jdbc.update("INSERT INTO users VALUES(?)",id);jdbc.update("INSERT INTO user_balances VALUES(?,'mKRW',1000000,0)",id);}
        now=new AtomicReference<>(Instant.ofEpochSecond(1000));wake=new AtomicInteger();nonce=new AtomicInteger();market=mock(FuturesMarketGate.class);
        when(market.now()).thenAnswer(x->now.get());when(market.capture()).thenAnswer(x->new FuturesMarketGate.Mark(FuturesLedgerTest.ENTRY,now.get(),"SIMULATED",null));
        var access=new AuthorizedFuturesService.ChainAccess(){public void verify(){}public boolean eoa(String w){return true;}public BigInteger nonce(String w){return BigInteger.valueOf(nonce.get());}};
        service=new AuthorizedFuturesService(store,ledger,market,CHAIN,PRICE,access,wake::incrementAndGet,ORIGIN);
        rpc=mock(FuturesRpcGateway.class);when(rpc.pendingNonce(anyString())).thenAnswer(x->BigInteger.valueOf(jdbc.queryForObject("SELECT count(*) FROM futures_transactions",Long.class)));
        when(rpc.gasPrice()).thenReturn(BigInteger.ONE);when(rpc.estimate(anyString(),any(),any(),anyString(),anyString())).thenReturn(BigInteger.valueOf(500000));
        when(rpc.broadcast(anyString())).thenAnswer(x->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_transactions WHERE raw_transaction=?",Long.class,(Object)x.getArgument(0))).isEqualTo(1);throw new java.io.IOException("FAKE_RESPONSE_LOSS");});
        sender=new FuturesTransactionSender(CHAIN,EXEC,txs,rpc);
    }
    @AfterEach void close(){context.close();}
    static String sig(byte[] hash,Credentials c){var s=Sign.signMessage(hash,c.getEcKeyPair(),false);byte[] b=new byte[65];System.arraycopy(s.getR(),0,b,0,32);System.arraycopy(s.getS(),0,b,32,32);b[64]=s.getV()[0];return Numeric.toHexString(b);}
    void link(long uid,Credentials key){var c=service.challenge(uid,key.getAddress(),ORIGIN);service.link(uid,c.id(),sig(Sign.getEthereumMessageHash(c.message().getBytes(java.nio.charset.StandardCharsets.UTF_8)),key),ORIGIN);}
    String key(){return UUID.randomUUID().toString();}
    AuthorizedFuturesService.Request request(){return new AuthorizedFuturesService.Request(Action.OPEN,null,0,Side.LONG,10,FuturesLedgerTest.C);}
    String signature(AuthorizedFuturesService.DraftView v){var d=store.draft(1,v.id(),false);return sig(UserIntentCodec.digest(CHAIN,d.report(),d.authorization().approval()),USER);}
    Operation accepted(){link(1,USER);String k=key();var v=service.draft(1,k,request());return service.submit(1,v.id(),k,signature(v));}
    FuturesTransactionStore.Stored signed(Operation op){var f=store.fixedReport(op.id()).orElseThrow();sender.submit(op.id(),f.getKey(),f.getValue());return txs.find(op.id()).orElseThrow();}
    @Test void draftDoesNotReserveAndAcceptanceLinksExactIdsAndRejectsLegacyBypass(){
        link(1,USER);String key=key();var d=service.draft(1,key,request());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isZero();
        Operation op=service.submit(1,d.id(),key,signature(d));assertThat(op.id()).isEqualTo(d.operationId());assertThat(op.positionId()).isEqualTo(d.positionId());
        assertThat(ledger.position(op.positionId(),1).accountRef()).isEqualTo(UserIntentCodec.account(USER.getAddress()));
        assertThat(service.submit(1,d.id(),key,signature(d)).id()).isEqualTo(op.id());assertThat(wake.get()).isEqualTo(2);
        assertThatThrownBy(()->ledger.prepare(1,op.positionId(),key(),Action.CLOSE,0,FuturesLedgerTest.ENTRY,1000,BigInteger.ZERO)).hasMessage("FUTURES_CONTRACT_VERSION_CONFLICT");
        var old=new FuturesIntentStore(context.getBean(DataSource.class),ledger);assertThat(old.prepared()).isEmpty();
    }
    @Test void walletProofOriginIdentityExpiryReplayAndDuplicateOwner(){
        assertThatThrownBy(()->service.challenge(1,USER.getAddress(),"http://evil.invalid")).hasMessage("WALLET_ORIGIN_CONFLICT");
        var c=service.challenge(1,USER.getAddress(),ORIGIN);String s=sig(Sign.getEthereumMessageHash(c.message().getBytes(java.nio.charset.StandardCharsets.UTF_8)),USER);
        assertThatThrownBy(()->service.link(2,c.id(),s,ORIGIN)).hasMessage("WALLET_CHALLENGE_NOT_FOUND");
        service.link(1,c.id(),s,ORIGIN);assertThatThrownBy(()->service.link(1,c.id(),s,ORIGIN)).hasMessage("WALLET_CHALLENGE_EXPIRED_OR_USED");
        var other=service.challenge(2,USER.getAddress(),ORIGIN);String duplicate=sig(Sign.getEthereumMessageHash(other.message().getBytes(java.nio.charset.StandardCharsets.UTF_8)),USER);
        assertThatThrownBy(()->service.link(2,other.id(),duplicate,ORIGIN)).hasMessage("WALLET_ALREADY_LINKED");
        var later=service.challenge(2,OTHER.getAddress(),ORIGIN);final String id=later.id();final String expired=sig(Sign.getEthereumMessageHash(later.message().getBytes(java.nio.charset.StandardCharsets.UTF_8)),OTHER);now.set(now.get().plusSeconds(121));
        assertThatThrownBy(()->service.link(2,id,expired,ORIGIN)).hasMessage("WALLET_CHALLENGE_EXPIRED_OR_USED");
    }
    @Test void invalidSignatureUserIsolationIdempotencyAndExpiryDoNotReserve(){
        link(1,USER);String k=key();var d=service.draft(1,k,request());String s=signature(d);
        assertThatThrownBy(()->service.submit(2,d.id(),k,s)).hasMessage("AUTHORIZED_DRAFT_NOT_FOUND");
        assertThatThrownBy(()->service.submit(1,d.id(),k,"0x"+"00".repeat(65))).hasMessage("USER_SIGNATURE_INVALID");
        assertThatThrownBy(()->service.submit(1,d.id(),key(),s)).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->service.draft(1,k,new AuthorizedFuturesService.Request(Action.OPEN,null,0,Side.SHORT,10,FuturesLedgerTest.C))).hasMessage("IDEMPOTENCY_CONFLICT");
        now.set(now.get().plusSeconds(31));assertThatThrownBy(()->service.submit(1,d.id(),k,s)).hasMessage("AUTHORIZED_DRAFT_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations",Long.class)).isZero();
    }
    @Test void concurrentSameSubmissionReservesOnlyOnce()throws Exception {
        link(1,USER);String k=key();var d=service.draft(1,k,request());String s=signature(d);var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);
        try{var a=pool.submit(()->{go.await();return service.submit(1,d.id(),k,s);});var b=pool.submit(()->{go.await();return service.submit(1,d.id(),k,s);});go.countDown();assertThat(a.get(8,TimeUnit.SECONDS).id()).isEqualTo(b.get(8,TimeUnit.SECONDS).id());}
        finally{pool.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal",Long.class)).isEqualTo(1);
    }
    @Test void durableSignedRawRecoveryAndReviewKeepLocks()throws Exception {
        var op=accepted();var first=signed(op);assertThat(first.status()).isEqualTo("SIGNED");
        doReturn(first.hash()).when(rpc).broadcast(first.raw());assertThat(sender.recover(op.id()).txHash()).isEqualTo(first.hash());
        assertThat(txs.find(op.id()).orElseThrow().raw()).isEqualTo(first.raw());verify(rpc,times(1)).pendingNonce(CHAIN.executor());verify(rpc,times(2)).broadcast(first.raw());
        ledger.markReview(op.id());txs.markSubmitted(op.id(),first.hash());assertThat(txs.find(op.id()).orElseThrow().status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isEqualByComparingTo("100000");
        assertThat(txs.pending(50)).isEmpty();assertThat(txs.pending(50,CHAIN.engine())).isEmpty();
    }
    String words(BigInteger... values){var s=new StringBuilder("0x");for(var v:values)s.append(Numeric.toHexStringNoPrefixZeroPadded(v.signum()<0?v.add(BigInteger.ONE.shiftLeft(256)):v,64));return s.toString();}
    Log log(String emitter,FuturesTransactionStore.Stored s,String topic,List<String> indexed,String data,int index){var l=new Log();var t=new ArrayList<String>();t.add(topic);t.addAll(indexed);l.setTopics(t);l.setAddress(emitter);l.setData(data);l.setTransactionHash(s.hash());l.setBlockHash("0x"+"55".repeat(32));l.setBlockNumber("0x7");l.setTransactionIndex("0x0");l.setLogIndex("0x"+index);return l;}
    FuturesReconciler receipt(Operation op,FuturesTransactionStore.Stored s)throws Exception {
        var r=s.report();var p=ledger.position(op.positionId(),op.userId());var auth=s.authorization();var a=auth.approval();var m=FuturesMath.mark(p.side(),p.quantity(),p.entryPrice(),r.collateralAfter(),r.priceE8());
        boolean end=op.action()==Action.CLOSE;BigInteger realized=end?m.realized():BigInteger.ZERO,debt=end?m.badDebt():BigInteger.ZERO;
        var applied=log(CHAIN.rules(),s,FuturesReceiptVerifier.APPLIED,List.of(r.operationId(),r.positionId()),words(BigInteger.valueOf(r.action()),r.expectedVersion().add(BigInteger.ONE),r.priceE8(),r.collateralAfter(),m.unrealized(),realized,debt,r.expectedCallEpoch(),BigInteger.ZERO),0);
        var consumed=log(CHAIN.rules(),s,FuturesReceiptVerifier.CONSUMED,List.of(r.operationId()),words(r.observedAt(),r.validUntil()),1);
        var user=log(CHAIN.engine(),s,FuturesReceiptVerifier.USER_CONSUMED,List.of(Numeric.toHexString(UserIntentCodec.digest(CHAIN,r,a)),"0x"+"0".repeat(24)+auth.owner().substring(2),r.positionId()),words(BigInteger.valueOf(r.action()),a.nonce()),2);
        var receipt=new TransactionReceipt();receipt.setTransactionHash(s.hash());receipt.setFrom(CHAIN.executor());receipt.setTo(CHAIN.engine());receipt.setBlockHash(applied.getBlockHash());receipt.setBlockNumber("0x7");receipt.setTransactionIndex("0x0");receipt.setStatus("0x1");receipt.setLogs(List.of(applied,consumed,user));
        var tx=new Transaction();tx.setHash(s.hash());tx.setFrom(CHAIN.executor());tx.setTo(CHAIN.engine());tx.setNonce(Numeric.encodeQuantity(BigInteger.valueOf(s.nonce())));tx.setValue("0x0");tx.setInput(UserIntentCodec.encode(r,s.signature(),auth));tx.setBlockHash(applied.getBlockHash());tx.setBlockNumber("0x7");tx.setTransactionIndex("0x0");
        var block=new EthBlock.Block();block.setHash(applied.getBlockHash());block.setNumber("0x7");block.setTimestamp(Numeric.encodeQuantity(r.observedAt()));
        when(rpc.receipt(s.hash())).thenReturn(Optional.of(receipt));when(rpc.transaction(s.hash())).thenReturn(Optional.of(tx));when(rpc.block(BigInteger.valueOf(7))).thenReturn(block);when(rpc.height()).thenReturn(BigInteger.valueOf(7));
        when(rpc.positionOwner(op.positionId(),BigInteger.valueOf(7))).thenReturn(auth.owner());
        when(rpc.position(op.positionId(),BigInteger.valueOf(7))).thenReturn(words(Numeric.toBigInt(r.accountRef()),BigInteger.valueOf(r.side()),BigInteger.valueOf(r.leverage()),r.quantity(),r.entryPriceE8(),r.collateralAfter(),BigInteger.valueOf(end?3:1),r.expectedVersion().add(BigInteger.ONE),r.expectedCallEpoch(),BigInteger.ZERO,r.observedAt()));
        return new FuturesReconciler(CHAIN,rpc,txs,sender,ledger,1);
    }
    @Test void realLocalSignaturesReceiptFixtureAndH2SettlementForOpenTopupClose()throws Exception {
        var op=accepted();var stored=signed(op);var reconciler=receipt(op,stored);assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.ALREADY_FINAL);nonce.incrementAndGet();
        for(Action action:List.of(Action.TOP_UP,Action.CLOSE)){
            var p=ledger.position(op.positionId(),1);String k=key();var d=service.draft(1,k,new AuthorizedFuturesService.Request(action,p.id(),p.version(),null,0,action==Action.TOP_UP?BigInteger.TEN.pow(22):BigInteger.ZERO));
            op=service.submit(1,d.id(),k,signature(d));stored=signed(op);assertThat(receipt(op,stored).reconcile(op.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);nonce.incrementAndGet();
        }
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE entry_type='CONFIRM'",Long.class)).isEqualTo(3);
        assertThat(ledger.position(op.positionId(),1).status()).isEqualTo("CLOSED");
    }
    @Test void wrongCanonicalOwnerQuarantinesWithoutSettlement()throws Exception {
        var op=accepted();var s=signed(op);var reconciler=receipt(op,s);when(rpc.positionOwner(op.positionId(),BigInteger.valueOf(7))).thenReturn(OTHER.getAddress());
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.REVIEW_REQUIRED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE entry_type='CONFIRM'",Long.class)).isZero();
    }
    @Test void chainUserNonceChangeAndStrictFiveSecondPolicyBlockBeforeReservation(){
        link(1,USER);String k=key();var d=service.draft(1,k,request());String s=signature(d);
        nonce.incrementAndGet();assertThatThrownBy(()->service.submit(1,d.id(),k,s)).hasMessage("USER_NONCE_CHANGED");nonce.set(0);
        doAnswer(x->{var mark=(FuturesMarketGate.Mark)x.getArgument(0);if(Duration.between(mark.observedAt(),now.get()).compareTo(Duration.ofSeconds(5))>0)throw new FuturesLedger.Failure("MARKET_UNAVAILABLE");return null;}).when(market).validate(any());
        now.set(now.get().plusSeconds(6));assertThatThrownBy(()->service.submit(1,d.id(),k,s)).hasMessage("MARKET_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations",Long.class)).isZero();
    }
    @Test void beforeSigningRecheckFailureReleasesOnlyUnsignedMargin(){
        var op=accepted();var intents=new AuthorizedIntentStore(context.getBean(DataSource.class),ledger,store);
        var execution=new FuturesExecution(ledger,intents,market,CHAIN,PRICE,txs,sender,id->{throw new FuturesLedger.Failure("USER_NONCE_OR_EOA_CHANGED");});
        assertThatThrownBy(()->execution.submitPrepared(op.id())).hasMessage("USER_NONCE_OR_EOA_CHANGED");assertThat(txs.find(op.id())).isEmpty();
        assertThat(ledger.operation(op.id()).state()).isEqualTo("REJECTED");assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=1",BigDecimal.class)).isZero();
    }
    @Test void automaticOperationsReuseRiskLedgerButRequireAuthorizedPosition()throws Exception {
        var op=accepted();var s=signed(op);assertThat(receipt(op,s).reconcile(op.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);
        var mark=new FuturesMarketGate.Mark(new BigInteger("7000000000000"),Instant.ofEpochSecond(1001),"SIMULATED",null);
        var call=store.automatic(1,op.positionId(),key(),Action.START_CALL,1,mark);
        assertThat(store.operation(call.id()).approval()).isNull();assertThat(new FuturesIntentStore(context.getBean(DataSource.class),ledger).prepared()).isEmpty();
        assertThat(new AuthorizedIntentStore(context.getBean(DataSource.class),ledger,store).prepared()).contains(call.id());
        assertThatThrownBy(()->store.automatic(1,op.positionId(),key(),Action.CLOSE,1,mark)).hasMessage("USER_SIGNATURE_REQUIRED");
    }
    @Test void legacyAuditDoesNotClaimCoverageForAuthorizedPositions(){
        accepted();assertThatThrownBy(()->com.pricetrack.exchange.futures.audit.FuturesAuditSnapshot.read(jdbc,new ObjectMapper())).isInstanceOf(com.pricetrack.exchange.futures.audit.FuturesAuditSnapshot.AuthorizedCoverageUnavailable.class);
    }
}
