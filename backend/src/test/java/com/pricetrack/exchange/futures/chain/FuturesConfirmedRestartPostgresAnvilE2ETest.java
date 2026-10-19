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
import java.nio.file.*;
import java.time.Instant;
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

/** Broadcast succeeds but acknowledgement is lost; a DIFFERENT JVM settles that already-mined, unchanged signed transaction. */
@EnabledIfEnvironmentVariable(named="FUTURES_CONFIRMED_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FuturesAutoPostgresAnvilE2ETest.FixtureMarket.class)
class FuturesConfirmedRestartPostgresAnvilE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry r)throws Exception {var p=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("FUTURES_CONFIRMED_E2E_RUN"));if(p==null)throw new IllegalStateException("NEW_CONFIRMED_RESTART_FIXTURE_REQUIRED");p.forEach((k,v)->r.add(k.toString(),()->v));}
    @Autowired FuturesLedger ledger;@Autowired FuturesService commands;@Autowired FuturesWorker worker;@Autowired FuturesMarketGate market;
    @Autowired FuturesTransactionStore store;@Autowired FuturesRpcGateway rpc;@Autowired FuturesChainIdentity chain;@Autowired FuturesReconciler reconciler;
    @Autowired AuthService auth;@Autowired WalletService wallets;@Autowired JdbcTemplate jdbc;@Autowired Environment env;
    String key(){return UUID.randomUUID().toString();}
    Path proof(){return Path.of(System.getenv("FUTURES_CONFIRMED_E2E_RUN")).resolve("confirmed-prepare.evidence.json");}
    void await(String code,int seconds,BooleanSupplier condition)throws Exception {long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!condition.getAsBoolean()){if(System.nanoTime()>stop)throw new IllegalStateException(code);Thread.sleep(100);}}
    @Test void minedSignedIntentIsConfirmedInDifferentJvmWithoutResigning()throws Exception {
        String stage=System.getenv("FUTURES_RESTART_STAGE");if("prepare".equals(stage))prepare();else if("recover".equals(stage))recover();else throw new IllegalStateException("EXPLICIT_STAGE_REQUIRED");
    }
    void prepare()throws Exception {
        await("CLOCK_BLOCKED",25,()->{try{return rpc.block(rpc.height()).getTimestamp().longValueExact()<=Instant.now().getEpochSecond()+1;}catch(Exception e){return false;}});
        worker.close();ledger.initializeEmptyFixture();auth.signup(new SignupRequest("confirmed_restart","fixture-password","restart"));long uid=jdbc.queryForObject("SELECT id FROM users WHERE login_id='confirmed_restart'",Long.class);wallets.faucet(uid,key());
        var v=commands.preview(uid,Action.OPEN,null,0,Side.LONG,10,BigInteger.TEN.pow(23));var op=commands.consume(uid,v.id(),key(),Action.OPEN,null,0);var p=ledger.position(op.positionId(),uid);
        var executor=Credentials.create(env.getRequiredProperty("app.futures.executor-private-key"));var signer=Credentials.create(env.getRequiredProperty("app.futures.signer-private-key"));
        var lostAcknowledgement=new FuturesTransactionSender.Transport(){
            public void verifyDeployment(FuturesChainIdentity c)throws IOException{rpc.verifyDeployment(c);}public BigInteger pendingNonce(String a)throws IOException{return rpc.pendingNonce(a);}public BigInteger gasPrice()throws IOException{return rpc.gasPrice();}
            public BigInteger estimate(String a,BigInteger n,BigInteger g,String e,String d)throws IOException{return rpc.estimate(a,n,g,e,d);}public boolean known(String h)throws IOException{return rpc.known(h);}
            public String broadcast(String raw)throws IOException{rpc.broadcast(raw);throw new IOException("FIXTURE_ACK_LOST_AFTER_SEND");}
        };
        var report=FuturesReport.from(op,p,chain.executor());var submitted=new FuturesTransactionSender(chain,executor,store,lostAcknowledgement).submit(op.id(),report,FuturesReportCodec.sign(report,chain.chainId(),chain.engine(),signer.getEcKeyPair()));
        assertThat(submitted.status()).isEqualTo("SIGNED");
        await("MINING_TIMEOUT",15,()->{try{return rpc.receipt(submitted.txHash()).isPresent();}catch(Exception e){return false;}});
        var receipt=rpc.receipt(submitted.txHash()).orElseThrow();assertThat(receipt.isStatusOK()).isTrue();assertThat(ledger.operation(op.id()).state()).isEqualTo("SIGNED");
        var tx=store.find(op.id()).orElseThrow();var block=rpc.block(receipt.getBlockNumber());assertThat(block.getTimestamp()).isLessThanOrEqualTo(report.validUntil());
        Files.writeString(proof(),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("processId",ProcessHandle.current().pid(),"userId",uid,"positionId",p.id(),"operationId",op.id(),"txHash",tx.hash(),"nonce",tx.nonce(),"blockNumber",receipt.getBlockNumber(),"blockHash",receipt.getBlockHash(),"receiptSucceededBeforeRestart",true)),StandardOpenOption.CREATE_NEW);
    }
    void recover()throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var old=json.readTree(proof().toFile());assertThat(old.path("processId").asLong()).isNotEqualTo(ProcessHandle.current().pid());String opid=old.path("operationId").asText(),pid=old.path("positionId").asText();long uid=old.path("userId").asLong();
        await("CONFIRMED_RECOVERY_TIMEOUT",25,()->ledger.operation(opid).state().equals("CONFIRMED"));var tx=store.find(opid).orElseThrow();
        assertThat(tx.hash()).isEqualTo(old.path("txHash").asText());assertThat(tx.nonce()).isEqualTo(old.path("nonce").asLong());assertThat(Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(tx.raw())))).isEqualTo(tx.hash());
        var receipt=rpc.receipt(tx.hash()).orElseThrow();assertThat(receipt.isStatusOK()).isTrue();assertThat(receipt.getBlockHash()).isEqualTo(old.path("blockHash").asText());assertThat(rpc.block(receipt.getBlockNumber()).getHash()).isEqualTo(receipt.getBlockHash());
        var before=jdbc.queryForMap("SELECT amount,locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",uid);reconciler.reconcile(opid);reconciler.reconcile(opid);assertThat(jdbc.queryForMap("SELECT amount,locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",uid)).isEqualTo(before);
        assertThat(ledger.position(pid,uid).status()).isEqualTo("OPEN");assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_journal WHERE operation_id=? AND entry_type='CONFIRM'",Long.class,opid)).isEqualTo(1);
        assertThat((BigDecimal)before.get("amount")).isEqualByComparingTo("1000000");assertThat((BigDecimal)before.get("locked_amount")).isEqualByComparingTo("100000");
        Files.writeString(proof().resolveSibling("confirmed-recovered.evidence.json"),json.writeValueAsString(Map.of("processId",ProcessHandle.current().pid(),"txHash",tx.hash(),"nonce",tx.nonce(),"sameRawHash",true,"state",tx.status(),"canonicalReceipt",true,"singleSettlementJournal",true)),StandardOpenOption.CREATE_NEW);
    }
}
