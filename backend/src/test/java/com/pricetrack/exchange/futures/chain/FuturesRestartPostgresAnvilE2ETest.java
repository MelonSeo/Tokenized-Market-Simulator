package com.pricetrack.exchange.futures.chain;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.auth.AuthController.SignupRequest;
import com.pricetrack.exchange.auth.AuthService;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.runtime.*;
import com.pricetrack.exchange.wallet.WalletService;
import java.io.IOException;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

/** phase-a invokes this class in TWO separate Gradle test JVMs against one owned, never-reset child fixture. */
@EnabledIfEnvironmentVariable(named="FUTURES_RESTART_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FuturesAutoPostgresAnvilE2ETest.FixtureMarket.class)
class FuturesRestartPostgresAnvilE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry r)throws Exception {
        var p=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("FUTURES_RESTART_E2E_RUN"));
        if(p==null || !p.getProperty("app.futures.runtime-enabled","").equals("true"))throw new IllegalStateException("NEW_RESTART_FIXTURE_REQUIRED");p.forEach((k,v)->r.add(k.toString(),()->v));
    }
    @Autowired FuturesLedger ledger;@Autowired FuturesIntentStore intents;@Autowired FuturesMarketGate market;
    @Autowired FuturesTransactionStore store;@Autowired FuturesRpcGateway rpc;@Autowired FuturesChainIdentity chain;@Autowired FuturesWorker worker;
    @Autowired FuturesService commands;@Autowired AuthService auth;@Autowired WalletService wallets;@Autowired JdbcTemplate jdbc;@Autowired Environment env;
    @Autowired FuturesAutoPostgresAnvilE2ETest.Scenario scenario;
    void await(String reason,int seconds,BooleanSupplier predicate)throws Exception {long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!predicate.getAsBoolean()){if(System.nanoTime()>stop)throw new IllegalStateException(reason);Thread.sleep(100);}}
    java.nio.file.Path proof(){return java.nio.file.Path.of(System.getenv("FUTURES_RESTART_E2E_RUN")).resolve("restart-prepare.evidence.json");}
    @Test void preparedSignedRawSurvivesSeparateJvmAndRiskMonitoringResumes()throws Exception {
        if("prepare".equals(System.getenv("FUTURES_RESTART_STAGE")))prepare();
        else if("recover".equals(System.getenv("FUTURES_RESTART_STAGE")))recover();
        else throw new IllegalStateException("EXPLICIT_RESTART_STAGE_REQUIRED");
    }
    void prepare()throws Exception {
        await("RESTART_CLOCK_BLOCKED",25,()->{try{return rpc.block(rpc.height()).getTimestamp().longValueExact()<=Instant.now().getEpochSecond()+1;}catch(Exception e){return false;}});
        ledger.initializeEmptyFixture();auth.signup(new SignupRequest("restart_future","fixture-password","restart"));
        long uid=jdbc.queryForObject("SELECT id FROM users WHERE login_id='restart_future'",Long.class);wallets.faucet(uid,UUID.randomUUID().toString());
        var v=commands.preview(uid,Action.OPEN,null,0,Side.LONG,10,BigInteger.TEN.pow(23));var opened=commands.consume(uid,v.id(),UUID.randomUUID().toString(),Action.OPEN,null,0);
        await("RESTART_OPEN_TIMEOUT",20,()->ledger.position(opened.positionId(),uid).status().equals("OPEN"));scenario.price("60000");
        await("RESTART_CALL_TIMEOUT",25,()->ledger.position(opened.positionId(),uid).riskStatus().equals("GRACE"));
        // Stop only this fixture's worker. The next JVM starts a new worker from persisted intent/state.
        worker.close();Thread.sleep(200);var p=ledger.position(opened.positionId(),uid);var mark=market.capture();
        var op=intents.prepare(uid,p.id(),UUID.randomUUID().toString(),Action.TOP_UP,p.version(),BigInteger.TEN.pow(23),mark);
        var executor=Credentials.create(env.getRequiredProperty("app.futures.executor-private-key"));var signer=Credentials.create(env.getRequiredProperty("app.futures.signer-private-key"));
        var outage=new FuturesTransactionSender.Transport(){
            public void verifyDeployment(FuturesChainIdentity c)throws IOException{rpc.verifyDeployment(c);}public BigInteger pendingNonce(String a)throws IOException{return rpc.pendingNonce(a);}public BigInteger gasPrice()throws IOException{return rpc.gasPrice();}
            public BigInteger estimate(String a,BigInteger n,BigInteger g,String e,String d)throws IOException{return rpc.estimate(a,n,g,e,d);}public boolean known(String h)throws IOException{return rpc.known(h);}
            public String broadcast(String raw)throws IOException{throw new IOException("FIXTURE_OUTAGE_BEFORE_SEND");}
        };
        var report=FuturesReport.from(op,p,chain.executor());var signed=new FuturesTransactionSender(chain,executor,store,outage).submit(op.id(),report,FuturesReportCodec.sign(report,chain.chainId(),chain.engine(),signer.getEcKeyPair()),()->market.validate(mark));
        assertThat(signed.status()).isEqualTo("SIGNED");var tx=store.find(op.id()).orElseThrow();
        var proof=Map.of("processId",ProcessHandle.current().pid(),"userId",uid,"positionId",p.id(),"operationId",op.id(),"txHash",tx.hash(),"nonce",tx.nonce(),"deadline",p.deadline(),"createdEpochMillis",Instant.now().toEpochMilli());
        java.nio.file.Files.writeString(proof(),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(proof),java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    void recover()throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var previous=json.readTree(proof().toFile());assertThat(previous.path("processId").asLong()).isNotEqualTo(ProcessHandle.current().pid());
        long uid=previous.path("userId").asLong();String pid=previous.path("positionId").asText(),opid=previous.path("operationId").asText();
        await("RESTART_SAME_RAW_TIMEOUT",35,()->Set.of("CONFIRMED","REVERTED").contains(ledger.operation(opid).state()));
        var tx=store.find(opid).orElseThrow();assertThat(tx.hash()).isEqualTo(previous.path("txHash").asText());assertThat(tx.nonce()).isEqualTo(previous.path("nonce").asLong());
        assertThat(Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(tx.raw())))).isEqualTo(tx.hash());
        // A startup delay may expire the original30s report. A real status0 is valid recovery, not a fabricated success.
        var receipt=rpc.receipt(tx.hash()).orElseThrow();assertThat(rpc.block(receipt.getBlockNumber()).getHash()).isEqualTo(receipt.getBlockHash());
        assertThat(receipt.isStatusOK()).isEqualTo(ledger.operation(opid).state().equals("CONFIRMED"));
        await("RESTART_AUTO_LIQUIDATION_TIMEOUT",95,()->ledger.position(pid,uid).status().equals("LIQUIDATED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type IN ('CONFIRM','REVERT_RELEASE')",Long.class,opid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(locked_amount) FROM user_balances",BigDecimal.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(wallet_delta+house_delta) FROM futures_journal",BigDecimal.class)).isZero();
        java.nio.file.Files.writeString(proof().resolveSibling("restart-recovered.evidence.json"),json.writeValueAsString(Map.of("processId",ProcessHandle.current().pid(),"txHash",tx.hash(),"nonce",tx.nonce(),"sameRawHash",true,"recoveredTransactionState",ledger.operation(opid).state(),"riskMonitoringResumed",true,"positionState",ledger.position(pid,uid).status())),java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
