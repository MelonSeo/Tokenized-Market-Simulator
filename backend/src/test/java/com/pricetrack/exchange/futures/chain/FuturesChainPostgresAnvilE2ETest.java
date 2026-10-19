package com.pricetrack.exchange.futures.chain;

import com.pricetrack.exchange.auth.AuthController.SignupRequest;
import com.pricetrack.exchange.auth.AuthService;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.wallet.WalletService;
import java.io.IOException;
import java.math.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.web3j.crypto.*;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;

/** NEW guarded PG/Anvil, actual signatures/receipts. Prices below are explicit synthetic fixtures, not live-provider acceptance. */
@EnabledIfEnvironmentVariable(named="FUTURES_CHAIN_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class FuturesChainPostgresAnvilE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry r)throws Exception {
        var p=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("FUTURES_CHAIN_E2E_RUN"));
        if(p==null || !p.getProperty("app.futures.enabled","").equals("true")) throw new IllegalStateException("New futures fixture required");
        p.forEach((k,v)->r.add(k.toString(),()->v));
    }
    @Autowired FuturesLedger ledger; @Autowired FuturesTransactionStore store;
    @Autowired AuthService auth; @Autowired WalletService wallets; @Autowired JdbcTemplate jdbc; @Autowired Environment env;
    static final BigInteger C=BigInteger.TEN.pow(23),ENTRY=new BigInteger("7500000000000");
    HttpService service; FuturesRpcGateway rpc; FuturesTransactionSender sender; FuturesReconciler reconciler;
    FuturesChainIdentity chain; Credentials signer,executor;
    String key() { return UUID.randomUUID().toString(); }
    long user(String login) {
        login=login.toLowerCase(Locale.ROOT); // AuthService's existing ID contract is case-normalized.
        auth.signup(new SignupRequest(login,"new-fixture-password",login));
        long uid=jdbc.queryForObject("SELECT id FROM users WHERE login_id=?",Long.class,login); wallets.faucet(uid,key()); return uid;
    }
    long observed()throws Exception {
        // Do not silently sign a future chain clock: wait only within a small bounded clock-precondition window.
        long stop=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(rpc.block(rpc.height()).getTimestamp().longValueExact()>Instant.now().getEpochSecond()+1) {
            if(System.nanoTime()>stop) throw new IllegalStateException("CHAIN_CLOCK_PRECONDITION_FAILED"); Thread.sleep(50);
        }
        return Instant.now().getEpochSecond();
    }
    void clockReadyBeforeAssets()throws Exception {
        var samples=new ArrayList<Map<String,Long>>();long stop=System.nanoTime()+java.time.Duration.ofSeconds(25).toNanos();int stable=0;
        try {
            while(System.nanoTime()<stop) {
                long before=Instant.now().toEpochMilli(),block=rpc.block(rpc.height()).getTimestamp().longValueExact(),after=Instant.now().toEpochMilli();
                samples.add(Map.of("jvmBeforeMillis",before,"chainTimestamp",block,"jvmAfterMillis",after));
                if(block<=after/1000+1)stable++;else stable=0;
                if(stable>=3)return;
                Thread.sleep(200);
            }
            throw new IllegalStateException("POST_DEPLOY_CHAIN_CLOCK_BLOCKED");
        } finally { evidence("post-deploy-clock",Map.of("samples",samples,"stableSamples",stable,"noTimestampOverride",true)); }
    }
    Operation open(long uid,Side side,int l)throws Exception { return ledger.prepareOpen(uid,key(),side,C,l,ENTRY,observed()); }
    Operation action(long uid,String pid,Action a,long price,BigInteger extra)throws Exception {
        Position p=ledger.position(pid,uid); return ledger.prepare(uid,pid,key(),a,p.version(),BigInteger.valueOf(price).multiply(BigInteger.TEN.pow(8)),observed(),extra);
    }
    void execute(Operation op)throws Exception {
        var p=ledger.position(op.positionId(),op.userId()); var r=FuturesReport.from(op,p,chain.executor());
        if(op.action()==Action.LIQUIDATE)awaitCanonical(r.observedAt().longValueExact());
        String signature=FuturesReportCodec.sign(r,chain.chainId(),chain.engine(),signer.getEcKeyPair());
        var sent=sender.submit(op.id(),r,signature); assertThat(sent.status()).isIn("SIGNED","SUBMITTED");
        long stop=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos(); FuturesReconciler.Result result;
        do { result=reconciler.reconcile(op.id()); if(result==FuturesReconciler.Result.CONFIRMED) break;
            assertThat(result).isIn(FuturesReconciler.Result.WAITING,FuturesReconciler.Result.RPC_UNAVAILABLE); Thread.sleep(100);
        } while(System.nanoTime()<stop);
        assertThat(result).isEqualTo(FuturesReconciler.Result.CONFIRMED);
        var stored=store.find(op.id()).orElseThrow(); var receipt=rpc.receipt(stored.hash()).orElseThrow();
        assertThat(receipt.isStatusOK()).isTrue(); assertThat(receipt.getLogs()).hasSize(2);
        assertThat(rpc.block(receipt.getBlockNumber()).getHash()).isEqualTo(receipt.getBlockHash());
        assertThat(reconciler.reconcile(op.id())).isEqualTo(FuturesReconciler.Result.ALREADY_FINAL);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='CONFIRM'",Long.class,op.id())).isEqualTo(1);
    }
    BigInteger wallet(long uid,String field) { return TokenUnits.toWei(jdbc.queryForObject("SELECT "+field+" FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,uid)); }
    void awaitCanonical(long minimum)throws Exception {
        long stop=System.nanoTime()+java.time.Duration.ofSeconds(4).toNanos();
        while(rpc.block(rpc.height()).getTimestamp().longValueExact()<minimum) {
            if(System.nanoTime()>stop)throw new IllegalStateException("CANONICAL_CLOCK_NOT_READY");Thread.sleep(50);
        }
    }
    void evidence(String name,Map<String,?> data)throws Exception {
        var run=java.nio.file.Path.of(System.getenv("FUTURES_CHAIN_E2E_RUN")).toRealPath();
        java.nio.file.Files.writeString(run.resolve(name+".evidence.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data),java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    void concurrentSettle(String id)throws Exception {
        // Interval mining is asynchronous: prove that the receipt exists BEFORE racing settlement, without settling it here.
        String hash=store.find(id).orElseThrow().hash();long stop=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
        while(rpc.receipt(hash).isEmpty()) {
            if(System.nanoTime()>stop)throw new IllegalStateException("CONCURRENT_RECEIPT_NOT_READY");Thread.sleep(100);
        }
        var readyReceipt=rpc.receipt(hash).orElseThrow();
        assertThat(readyReceipt.isStatusOK()).isTrue();
        assertThat(rpc.block(readyReceipt.getBlockNumber()).getHash()).isEqualTo(readyReceipt.getBlockHash());
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var tasks=new ArrayList<Future<FuturesReconciler.Result>>();
            for(int i=0;i<2;i++)tasks.add(pool.submit(()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("barrier");return reconciler.reconcile(id);}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(var t:tasks)assertThat(t.get(15,TimeUnit.SECONDS)).isIn(FuturesReconciler.Result.CONFIRMED,FuturesReconciler.Result.ALREADY_FINAL);
        } finally { start.countDown();pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='CONFIRM'",Long.class,id)).isEqualTo(1);
    }

    @Test void actualEngineReceiptsLossRecoveryGraceAndSameRaw()throws Exception {
        executor=Credentials.create(env.getRequiredProperty("app.futures.executor-private-key"));
        signer=Credentials.create(env.getRequiredProperty("app.futures.signer-private-key"));
        assertThat(executor.getAddress()).isNotEqualTo(Credentials.create(env.getRequiredProperty("app.blockchain.operator-private-key")).getAddress());
        chain=new FuturesChainIdentity(BigInteger.valueOf(31337),env.getRequiredProperty("app.futures.engine-address"),executor.getAddress(),signer.getAddress());
        service=new HttpService(env.getRequiredProperty("app.blockchain.rpc-url"));
        try {
            rpc=new FuturesRpcGateway(service,chain,env.getRequiredProperty("app.futures.code-hash"));
            rpc.verifyDeployment(chain); sender=new FuturesTransactionSender(chain,executor,store,rpc);
            reconciler=new FuturesReconciler(chain,rpc,store,sender,ledger,1);
            clockReadyBeforeAssets();ledger.initializeEmptyFixture();
            for(var side:Side.values()) for(int l:new int[]{2,5,10}) {
                long uid=user("future_"+side+"_"+l); var opened=open(uid,side,l); execute(opened);
                assertThat(wallet(uid,"locked_amount")).isEqualTo(C);
                var close=action(uid,opened.positionId(),Action.CLOSE,side==Side.LONG?75750:74250,BigInteger.ZERO); execute(close);
                assertThat(wallet(uid,"amount")).isEqualTo(BigInteger.TEN.pow(24).add(close.expectedRealized()));
                assertThat(wallet(uid,"locked_amount")).isZero();
            }
            long recovering=user("future_recovery"); var recovered=open(recovering,Side.LONG,10);execute(recovered);
            execute(action(recovering,recovered.positionId(),Action.START_CALL,70000,BigInteger.ZERO));
            execute(action(recovering,recovered.positionId(),Action.CLEAR_CALL,74000,BigInteger.ZERO));
            assertThat(ledger.position(recovered.positionId(),recovering).riskStatus()).isEqualTo("HEALTHY");
            execute(action(recovering,recovered.positionId(),Action.CLOSE,74000,BigInteger.ZERO));

            long topping=user("future_topup");var topped=open(topping,Side.SHORT,10);execute(topped);
            execute(action(topping,topped.positionId(),Action.START_CALL,80000,BigInteger.ZERO));
            execute(action(topping,topped.positionId(),Action.TOP_UP,80000,C));
            assertThat(wallet(topping,"locked_amount")).isEqualTo(C.multiply(BigInteger.TWO));
            assertThat(ledger.position(topped.positionId(),topping).riskStatus()).isEqualTo("HEALTHY");
            execute(action(topping,topped.positionId(),Action.CLOSE,80000,BigInteger.ZERO));

            // Both real 60-second grace periods overlap; no evm_increaseTime or synthetic receipt is used.
            var risky=new ArrayList<Operation>();long lastCallConfirmedNanos=0;
            for(var side:Side.values()) {
                long uid=user("future_liquidation_"+side);var op=open(uid,side,10);execute(op);
                execute(action(uid,op.positionId(),Action.START_CALL,side==Side.LONG?60000:90000,BigInteger.ZERO)); risky.add(op);
                lastCallConfirmedNanos=System.nanoTime();
            }
            long deadline=risky.stream().mapToLong(o->ledger.position(o.positionId(),o.userId()).deadline()).max().orElseThrow();
            long waitStop=System.nanoTime()+java.time.Duration.ofSeconds(70).toNanos();
            while(Instant.now().getEpochSecond()<deadline || rpc.block(rpc.height()).getTimestamp().longValueExact()<deadline || System.nanoTime()-lastCallConfirmedNanos<java.time.Duration.ofSeconds(60).toNanos()) {
                if(System.nanoTime()>waitStop) throw new IllegalStateException("GRACE_WAIT_TIMEOUT"); Thread.sleep(200);
            }
            for(var op:risky) {
                Side side=ledger.position(op.positionId(),op.userId()).side();
                var liquidation=action(op.userId(),op.positionId(),Action.LIQUIDATE,side==Side.LONG?60000:90000,BigInteger.ZERO);
                if(side==Side.LONG) {
                    // Preserve a concrete diagnosis: an idle last block precedes grace, while the pending execution is eligible.
                    var lr=FuturesReport.from(liquidation,ledger.position(op.positionId(),op.userId()),chain.executor());
                    String ls=FuturesReportCodec.sign(lr,chain.chainId(),chain.engine(),signer.getEcKeyPair());
                    var call=org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(chain.executor(),rpc.pendingNonce(chain.executor()),rpc.gasPrice(),null,chain.engine(),BigInteger.ZERO,FuturesReportCodec.encode(lr,ls));
                    var latest=new org.web3j.protocol.core.Request<Object,org.web3j.protocol.core.methods.response.EthEstimateGas>("eth_estimateGas",List.of(call,"latest"),service,org.web3j.protocol.core.methods.response.EthEstimateGas.class).send();
                    String code=latest.hasError()?FuturesRpcGateway.precheckCode(latest.getError().getData()):"NO_ERROR";
                    evidence("latest-gas-clock",Map.of("latestErrorCode",code,"latestTimestamp",rpc.block(rpc.height()).getTimestamp().toString(),
                        "observedAt",lr.observedAt().toString(),"deadline",ledger.position(op.positionId(),op.userId()).deadline(),"pendingTimestamp",rpc.pendingTimestamp()));
                    assertThat(code).isIn("NO_ERROR","FUTURES_INVALID_REPORT","FUTURES_GRACE_NOT_OVER");
                    System.out.printf("FUTURES_GAS_PRECHECK_LATEST=%s latest=%d observed=%d deadline=%d%n",code,
                        rpc.block(rpc.height()).getTimestamp().longValueExact(),lr.observedAt().longValueExact(),ledger.position(op.positionId(),op.userId()).deadline());
                    awaitCanonical(lr.observedAt().longValueExact());
                    assertThat(rpc.estimate(chain.executor(),rpc.pendingNonce(chain.executor()),rpc.gasPrice(),chain.engine(),FuturesReportCodec.encode(lr,ls))).isPositive();
                }
                execute(liquidation);
                var liquidatedReceipt=rpc.receipt(store.find(liquidation.id()).orElseThrow().hash()).orElseThrow();
                evidence("liquidation-"+side.name().toLowerCase(Locale.ROOT),Map.of("receiptValidation","CONFIRMED","txHash",liquidatedReceipt.getTransactionHash(),
                    "blockHash",liquidatedReceipt.getBlockHash(),"blockTimestamp",rpc.block(liquidatedReceipt.getBlockNumber()).getTimestamp().toString(),
                    "deadline",ledger.position(op.positionId(),op.userId()).deadline(),"observedAt",liquidation.observedAt(),
                    "elapsedSinceLastCallMillis",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-lastCallConfirmedNanos)));
                assertThat(ledger.position(op.positionId(),op.userId()).status()).isEqualTo("LIQUIDATED");
                assertThat(liquidation.expectedBadDebt()).isPositive();
                assertThat(wallet(op.userId(),"amount")).isEqualTo(BigInteger.TEN.pow(24).subtract(C));
                assertThat(wallet(op.userId(),"locked_amount")).isZero();
            }

            long lost=user("future_lost_response"); var op=open(lost,Side.LONG,5);
            // Send to actual Anvil, then deliberately drop the successful response at the client seam.
            var drop=new FuturesTransactionSender.Transport() {
                public void verifyDeployment(FuturesChainIdentity c)throws IOException { rpc.verifyDeployment(c); }
                public BigInteger pendingNonce(String s)throws IOException { return rpc.pendingNonce(s); }
                public BigInteger gasPrice()throws IOException { return rpc.gasPrice(); }
                public BigInteger estimate(String s,BigInteger n,BigInteger g,String e,String d)throws IOException { return rpc.estimate(s,n,g,e,d); }
                public boolean known(String h)throws IOException { return rpc.known(h); }
                public String broadcast(String raw)throws IOException { rpc.broadcast(raw);throw new IOException("INJECTED_RESPONSE_LOSS"); }
            };
            var r=FuturesReport.from(op,ledger.position(op.positionId(),lost),chain.executor());
            assertThat(new FuturesTransactionSender(chain,executor,store,drop).submit(op.id(),r,FuturesReportCodec.sign(r,chain.chainId(),chain.engine(),signer.getEcKeyPair())).status()).isEqualTo("SIGNED");
            var before=store.find(op.id()).orElseThrow();
            // A new sender instance simulates process-local memory loss, not an actual JVM restart.
            assertThat(new FuturesTransactionSender(chain,executor,store,rpc).recover(op.id()).status()).isEqualTo("SUBMITTED");
            var after=store.find(op.id()).orElseThrow(); assertThat(after.raw()).isEqualTo(before.raw());
            assertThat(after.hash()).isEqualTo(before.hash());assertThat(after.nonce()).isEqualTo(before.nonce());
            concurrentSettle(op.id());
            execute(action(lost,op.positionId(),Action.CLOSE,75000,BigInteger.ZERO));

            long expired=user("future_expired_raw");var failed=open(expired,Side.SHORT,2);
            var failReport=FuturesReport.from(failed,ledger.position(failed.positionId(),expired),chain.executor());
            // Sign/commit with a valid estimate, but simulate an outage BEFORE broadcast. The expired SAME raw must mine status0.
            var outage=new FuturesTransactionSender.Transport() {
                public void verifyDeployment(FuturesChainIdentity c)throws IOException { rpc.verifyDeployment(c); }
                public BigInteger pendingNonce(String s)throws IOException { return rpc.pendingNonce(s); }
                public BigInteger gasPrice()throws IOException { return rpc.gasPrice(); }
                public BigInteger estimate(String s,BigInteger n,BigInteger g,String e,String d)throws IOException { return rpc.estimate(s,n,g,e,d); }
                public boolean known(String h)throws IOException { return rpc.known(h); }
                public String broadcast(String raw)throws IOException { throw new IOException("INJECTED_OUTAGE_BEFORE_SEND"); }
            };
            long signedAtNanos=System.nanoTime();
            assertThat(new FuturesTransactionSender(chain,executor,store,outage).submit(failed.id(),failReport,FuturesReportCodec.sign(failReport,chain.chainId(),chain.engine(),signer.getEcKeyPair())).status()).isEqualTo("SIGNED");
            long expireStop=System.nanoTime()+java.time.Duration.ofSeconds(45).toNanos();
            while(rpc.block(rpc.height()).getTimestamp().longValueExact()<=failReport.validUntil().longValueExact() || System.nanoTime()-signedAtNanos<java.time.Duration.ofSeconds(30).toNanos()) {
                if(System.nanoTime()>expireStop)throw new IllegalStateException("EXPIRY_WAIT_TIMEOUT");Thread.sleep(200);
            }
            long pendingAtRecovery=rpc.pendingTimestamp();
            var beforeFailure=store.find(failed.id()).orElseThrow();sender.recover(failed.id());
            long receiptStop=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();FuturesReconciler.Result failureResult;
            do {
                failureResult=reconciler.reconcile(failed.id());
                if(failureResult!=FuturesReconciler.Result.WAITING && failureResult!=FuturesReconciler.Result.RPC_UNAVAILABLE)break;
                if(System.nanoTime()>receiptStop)throw new IllegalStateException("EXPIRED_RECEIPT_TIMEOUT");Thread.sleep(100);
            }while(true);
            var expiredReceipt=rpc.receipt(beforeFailure.hash()).orElseThrow();long expiredBlockTime=rpc.block(expiredReceipt.getBlockNumber()).getTimestamp().longValueExact();
            evidence("expired-same-raw",Map.of("result",failureResult.name(),"receiptStatus",expiredReceipt.getStatus(),"blockTimestamp",expiredBlockTime,
                "validUntil",failReport.validUntil().toString(),"pendingAtRecovery",pendingAtRecovery,"observedAt",failReport.observedAt().toString(),
                "elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-signedAtNanos),"txHash",beforeFailure.hash()));
            assertThat(failureResult).isEqualTo(FuturesReconciler.Result.REVERTED);
            assertThat(expiredBlockTime).isGreaterThan(failReport.validUntil().longValueExact());
            assertThat(expiredReceipt.isStatusOK()).isFalse();
            assertThat(store.find(failed.id()).orElseThrow().raw()).isEqualTo(beforeFailure.raw());
            assertThat(store.find(failed.id()).orElseThrow().hash()).isEqualTo(beforeFailure.hash());
            assertThat(store.find(failed.id()).orElseThrow().nonce()).isEqualTo(beforeFailure.nonce());
            assertThat(wallet(expired,"amount")).isEqualTo(BigInteger.TEN.pow(24));assertThat(wallet(expired,"locked_amount")).isZero();
            assertThat(ledger.position(failed.positionId(),expired).status()).isEqualTo("OPEN_FAILED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_transactions WHERE status NOT IN ('CONFIRMED','REVERTED')",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE active_position_id IS NOT NULL",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT SUM(locked_amount) FROM user_balances",BigDecimal.class)).isZero();
            BigDecimal house=jdbc.queryForObject("SELECT amount FROM futures_house WHERE id=1",BigDecimal.class);
            BigDecimal delta=jdbc.queryForObject("SELECT SUM(house_delta) FROM futures_journal",BigDecimal.class);
            assertThat(house).isEqualByComparingTo(new BigDecimal(FuturesLedger.INITIAL_CAPITAL).add(delta));
            assertThat(jdbc.queryForObject("SELECT SUM(wallet_delta+house_delta) FROM futures_journal",BigDecimal.class)).isZero();
        } finally { if(rpc!=null)rpc.close();else service.close(); }
    }
}
