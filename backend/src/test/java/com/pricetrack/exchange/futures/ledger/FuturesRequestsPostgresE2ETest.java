package com.pricetrack.exchange.futures.ledger;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.runtime.*;
import com.pricetrack.exchange.wallet.WalletService;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/** Actual PostgreSQL locking, no invented blockchain receipt. Each user owns a new independent intent. */
@EnabledIfEnvironmentVariable(named="FUTURES_REQUESTS_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FuturesRequestsPostgresE2ETest.Config.class)
class FuturesRequestsPostgresE2ETest {
    @TestConfiguration static class Config {@Bean FuturesIntentStore intents(DataSource ds,FuturesLedger ledger){return new FuturesIntentStore(ds,ledger);}}
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry r)throws Exception {
        var p=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("FUTURES_REQUESTS_E2E_RUN"));
        if(p==null || p.getProperty("app.futures.runtime-enabled","false").equals("true"))throw new IllegalStateException("NEW_REQUEST_FIXTURE_REQUIRED");p.forEach((k,v)->r.add(k.toString(),()->v));
    }
    @Autowired FuturesLedger ledger;@Autowired FuturesIntentStore intents;@Autowired JdbcTemplate jdbc;@Autowired AuthService auth;@Autowired WalletService wallets;
    final BigInteger c=BigInteger.TEN.pow(23);
    long user(String name){auth.signup(new AuthController.SignupRequest(name,"fixture-password",name));long uid=jdbc.queryForObject("SELECT id FROM users WHERE login_id=?",Long.class,name);wallets.faucet(uid,UUID.randomUUID().toString());return uid;}
    FuturesIntentStore.Preview preview(long uid){var now=Instant.now();return intents.preview(uid,Action.OPEN,null,0,Side.LONG,10,c,new FuturesMarketGate.Mark(new BigInteger("7500000000000"),now,"SIMULATED",null),now);}
    @Test void actualPgRejectedFirstAcceptedFirstAndConcurrentRequestsAreSerialized()throws Exception {
        ledger.initializeEmptyFixture();
        long rejected=user("requests_rejected");var p=preview(rejected);String key=UUID.randomUUID().toString();
        assertThat(intents.rejectPreview(rejected,p.id(),key,Action.OPEN,null,0)).isEmpty();
        assertThatThrownBy(()->intents.consume(rejected,p.id(),key,Action.OPEN,null,0,Instant.now())).hasMessage("FUTURES_REQUEST_REJECTED");
        long accepted=user("requests_accepted");var a=preview(accepted);String ak=UUID.randomUUID().toString();var original=intents.consume(accepted,a.id(),ak,Action.OPEN,null,0,Instant.now());
        assertThat(intents.rejectPreview(accepted,a.id(),ak,Action.OPEN,null,0)).contains(original);ledger.rejectUnsigned(original.id(),"FIXTURE_UNSIGNED_RELEASE");
        var pool=Executors.newFixedThreadPool(2);
        try {for(int i=0;i<6;i++) {
            long uid=user("requests_race_"+i);var v=preview(uid);String rk=UUID.randomUUID().toString();var barrier=new CyclicBarrier(2);
            Future<String> submit=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{return intents.consume(uid,v.id(),rk,Action.OPEN,null,0,Instant.now()).id();}catch(FuturesLedger.Failure f){return f.code;}});
            Future<String> reject=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return intents.rejectPreview(uid,v.id(),rk,Action.OPEN,null,0).map(Operation::id).orElse("FUTURES_REQUEST_REJECTED");});
            String left=submit.get(10,TimeUnit.SECONDS),right=reject.get(10,TimeUnit.SECONDS);assertThat(left).isEqualTo(right);
            long count=jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE user_id=?",Long.class,uid);BigDecimal lock=jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,uid);
            if(left.equals("FUTURES_REQUEST_REJECTED")){assertThat(count).isZero();assertThat(lock).isZero();}
            else {assertThat(count).isEqualTo(1);assertThat(lock).isEqualByComparingTo("100000");assertThat(intents.consume(uid,v.id(),rk,Action.OPEN,null,0,Instant.now()).id()).isEqualTo(left);ledger.rejectUnsigned(left,"FIXTURE_UNSIGNED_RELEASE");}
        }}finally{pool.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT SUM(locked_amount) FROM user_balances",BigDecimal.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(wallet_delta+house_delta) FROM futures_journal",BigDecimal.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_transactions",Long.class)).isZero();
    }
}
