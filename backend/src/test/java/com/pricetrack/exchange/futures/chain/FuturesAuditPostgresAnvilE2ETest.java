package com.pricetrack.exchange.futures.chain;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.auth.AuthController.SignupRequest;
import com.pricetrack.exchange.auth.AuthService;
import com.pricetrack.exchange.auth.JwtTokenProvider;
import com.pricetrack.exchange.futures.audit.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.runtime.*;
import com.pricetrack.exchange.reserve.ReserveService;
import com.pricetrack.exchange.reserve.ReserveModels;
import com.pricetrack.exchange.wallet.WalletService;
import java.math.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/** Real PG/Anvil canonical audit; last deliberate corruption remains preserved, never repaired to manufacture a PASS. */
@EnabledIfEnvironmentVariable(named="FUTURES_AUDIT_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FuturesAutoPostgresAnvilE2ETest.FixtureMarket.class)
class FuturesAuditPostgresAnvilE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry r)throws Exception {
        var p=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("FUTURES_AUDIT_E2E_RUN"));
        if(p==null||!p.getProperty("app.futures.runtime-enabled","").equals("true"))throw new IllegalStateException("NEW_AUDIT_FIXTURE_REQUIRED");
        p.forEach((k,v)->r.add(k.toString(),()->v));
    }
    @Autowired FuturesLedger ledger;@Autowired FuturesService commands;@Autowired FuturesAuditService audits;
    @Autowired FuturesAuditStore store;@Autowired FuturesRpcGateway rpc;@Autowired ReserveService reserves;
    @Autowired AuthService auth;@Autowired WalletService wallets;@Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;@Autowired JwtTokenProvider jwt;
    @Autowired FuturesAutoPostgresAnvilE2ETest.Scenario scenario;
    String key(){return UUID.randomUUID().toString();}
    int get(String path,String token){var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);return http.exchange(path,HttpMethod.GET,new HttpEntity<>(h),String.class).getStatusCode().value();}
    void await(String code,int seconds,BooleanSupplier condition)throws Exception {long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!condition.getAsBoolean()){if(System.nanoTime()>stop)throw new IllegalStateException(code);Thread.sleep(100);}}
    FuturesAuditModels.Result stableAudit(long uid)throws Exception {
        FuturesAuditModels.Result result=null;
        for(int i=0;i<4;i++){result=audits.run(uid);if(!result.reason().equals("CUT_CHANGED"))return result;Thread.sleep(100);}
        return result;
    }
    /** New read-only observations only: never retry a trade, reset a baseline or promote an inconclusive result. */
    static ReserveModels.Result stableReserve(Supplier<ReserveModels.Result> observe)throws InterruptedException {
        ReserveModels.Result result=null;
        for(int i=0;i<4;i++) {
            result=observe.get();
            if(!"CUT_CHANGED".equals(result.reason()))return result;
            if(i<3)Thread.sleep(100);
        }
        return result;
    }
    @Test void canonicalHistoryDbConservationPendingAndDeliberateMismatch()throws Exception {
        await("CLOCK_BLOCKED",25,()->{try{return rpc.block(rpc.height()).getTimestamp().longValueExact()<=Instant.now().getEpochSecond()+1;}catch(Exception failure){return false;}});
        ledger.initializeEmptyFixture();
        var reserveBaseline=reserves.createBaseline(1);var baseline=audits.baseline(1);
        assertThat(baseline.initialHouse()).isEqualTo(FuturesLedger.INITIAL_CAPITAL.toString());
        assertThatThrownBy(()->audits.baseline(1)).hasMessageContaining("NOT_CREATABLE");
        auth.signup(new SignupRequest("audit_owner","fixture-password","audit owner"));long uid=jdbc.queryForObject("SELECT id FROM users WHERE login_id='audit_owner'",Long.class);wallets.faucet(uid,key());
        String user=jwt.createToken(uid,"audit_owner");
        assertThat(get("/api/admin/futures/audits",null)).isEqualTo(401);assertThat(get("/api/admin/futures/audits",user)).isEqualTo(403);
        auth.signup(new SignupRequest("audit_admin","fixture-password","audit admin"));long admin=jdbc.queryForObject("SELECT id FROM users WHERE login_id='audit_admin'",Long.class);jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?",admin);
        assertThat(get("/api/admin/futures/audits",jwt.createToken(admin,"audit_admin"))).isEqualTo(200);
        var c=BigInteger.TEN.pow(23);var preview=commands.preview(uid,Action.OPEN,null,0,Side.LONG,10,c);
        var opened=commands.consume(uid,preview.id(),key(),Action.OPEN,null,0);String pid=opened.positionId();
        var pending=audits.run(1);assertThat(pending.status()).isEqualTo("INCONCLUSIVE");assertThat(pending.reason()).isEqualTo("UNSETTLED_OR_REVIEW");
        await("OPEN_TIMEOUT",25,()->ledger.position(pid,uid).status().equals("OPEN"));
        var p=ledger.position(pid,uid);commands.margin(uid,pid,key(),p.version(),c);
        await("TOPUP_TIMEOUT",25,()->ledger.position(pid,uid).collateral().equals(c.multiply(BigInteger.TWO)));
        scenario.price("76000");p=ledger.position(pid,uid);var close=commands.preview(uid,Action.CLOSE,pid,p.version(),null,0,BigInteger.ZERO);commands.consume(uid,close.id(),key(),Action.CLOSE,pid,p.version());
        await("CLOSE_TIMEOUT",25,()->ledger.position(pid,uid).status().equals("CLOSED"));
        var balance=jdbc.queryForMap("SELECT amount,locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",uid);
        assertThat((BigDecimal)balance.get("locked_amount")).isZero();
        var result=stableAudit(1);assertThat(result.status()).as(result.reason()).isEqualTo("MATCH");assertThat(result.coverage().complete()).isTrue();assertThat(result.coverage().verifiedTransactions()).isEqualTo(3);assertThat(result.coverage().verifiedEvents()).isEqualTo(6);
        assertThat(result.items()).hasSize(3).allMatch(i->i.verdict().equals("MATCH"));
        var reserveMatch=stableReserve(()->reserves.run(1));
        assertThat(reserveMatch.status()).as(reserveMatch.reason()).isEqualTo("MATCH");
        assertThat(jdbc.queryForMap("SELECT amount,locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",uid)).isEqualTo(balance);
        assertThat(store.baseline().orElseThrow()).isEqualTo(baseline);assertThat(reserves.baseline()).contains(reserveBaseline);
        assertThat(store.result(result.id())).contains(result);
        var safe=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(result);assertThat(safe).doesNotContain("rawTransaction","raw_transaction","signature","private-key");
        assertThatThrownBy(()->jdbc.update("UPDATE futures_audit_baselines SET actor_id=2 WHERE id=1")).hasMessageContaining("append-only");
        assertThatThrownBy(()->jdbc.update("DELETE FROM futures_audit_runs")).hasMessageContaining("append-only");
        // Deliberate test-fixture mutation only. Preserve it and the resulting mismatches as evidence.
        jdbc.update("UPDATE futures_house SET amount=amount-1 WHERE id=1");
        var mismatch=stableAudit(1);assertThat(mismatch.status()).as(mismatch.reason()).isEqualTo("MISMATCH");assertThat(mismatch.coverage().complete()).isTrue();assertThat(mismatch.dbMismatchCount()).isPositive();
        var reserveMismatch=stableReserve(()->reserves.run(1));
        assertThat(reserveMismatch.status()).as(reserveMismatch.reason()).isEqualTo("MISMATCH");assertThat(store.baseline().orElseThrow()).isEqualTo(baseline);
        var folder=java.nio.file.Path.of(System.getenv("FUTURES_AUDIT_E2E_RUN")).toRealPath();
        java.nio.file.Files.writeString(folder.resolve("audit.evidence.json"),new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(Map.of("match",result,"deliberateMismatch",mismatch,"reserveMatch",reserveMatch,"reserveDeliberateMismatch",reserveMismatch,"dbWalletUnchanged",true,"baselineUnchanged",true)),java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
