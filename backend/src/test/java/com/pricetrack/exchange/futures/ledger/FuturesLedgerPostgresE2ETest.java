package com.pricetrack.exchange.futures.ledger;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.auth.AuthController.SignupRequest;
import com.pricetrack.exchange.auth.AuthService;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.wallet.WalletService;
import java.math.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real isolated PG concurrency/rollback only. Anvil is prepared by harness, but no futures tx is sent here. */
@EnabledIfEnvironmentVariable(named="FUTURES_LEDGER_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class FuturesLedgerPostgresE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry r)throws Exception {
        var p=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("FUTURES_LEDGER_E2E_RUN"));
        if(p==null) throw new IllegalStateException("New guarded Phase A futures fixture required");
        if(!p.getProperty("app.futures.enabled","").equals("true")) throw new IllegalStateException("Futures module must be explicit");
        p.forEach((k,v)->r.add(k.toString(),()->v));
    }
    @Autowired FuturesLedger ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired WalletService wallets;
    static final BigInteger C=new BigInteger("100000000000000000000000"),P=new BigInteger("7500000000000");
    String key() { return UUID.randomUUID().toString(); }
    long user(String login) {
        auth.signup(new SignupRequest(login,"fixture-only-password",login));
        long id=jdbc.queryForObject("SELECT id FROM users WHERE login_id=?",Long.class,login);
        wallets.faucet(id,key()); return id;
    }
    record Outcome(String id,String failure) {}
    Outcome attempt(long uid,String key) {
        try { return new Outcome(ledger.prepareOpen(uid,key,Side.LONG,C,10,P,1000).id(),null); }
        catch(FuturesLedger.Failure e) { return new Outcome(null,e.code); }
    }
    List<Outcome> race(long uid,String a,String b)throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var tasks=new ArrayList<Future<Outcome>>();
            for(String k:List.of(a,b)) tasks.add(pool.submit(()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("barrier");return attempt(uid,k);}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            return List.of(tasks.get(0).get(10,TimeUnit.SECONDS),tasks.get(1).get(10,TimeUnit.SECONDS));
        } finally { start.countDown();pool.shutdownNow(); }
    }
    @Test void freshPostgresConcurrencyRollbackJournalAndSpotCompatibility()throws Exception {
        ledger.initializeEmptyFixture(); ledger.initializeEmptyFixture();
        long first=user("futures_parallel"), second=user("futures_same_key"), third=user("futures_rollback");
        var different=race(first,key(),key());
        assertThat(different.stream().filter(x->x.id()!=null)).hasSize(1);
        assertThat(different.stream().filter(x->x.failure()!=null).findFirst().orElseThrow().failure()).isEqualTo("ACTIVE_POSITION_EXISTS");
        var firstOp=ledger.operation(different.stream().filter(x->x.id()!=null).findFirst().orElseThrow().id());
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,first)).isEqualByComparingTo("100000");
        ledger.rejectUnsigned(firstOp.id(),"FIXTURE_COMPLETE");
        String sameKey=key();var same=race(second,sameKey,sameKey);
        assertThat(same).allMatch(x->x.failure()==null);assertThat(same.get(0).id()).isEqualTo(same.get(1).id());
        ledger.rejectUnsigned(same.get(0).id(),"FIXTURE_COMPLETE");

        long before=jdbc.queryForObject("SELECT count(*) FROM futures_operations",Long.class);
        jdbc.execute("CREATE FUNCTION futures_fixture_fail_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture reserve rollback'; END $$");
        jdbc.execute("CREATE TRIGGER futures_fixture_fail BEFORE INSERT ON futures_journal FOR EACH ROW EXECUTE FUNCTION futures_fixture_fail_insert()");
        assertThatThrownBy(()->ledger.prepareOpen(third,key(),Side.SHORT,C,5,P,1000)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations",Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,third)).isEqualByComparingTo("0");
        jdbc.execute("DROP TRIGGER futures_fixture_fail ON futures_journal");
        jdbc.execute("DROP FUNCTION futures_fixture_fail_insert()");
        var retry=ledger.prepareOpen(third,key(),Side.SHORT,C,5,P,1000);ledger.rejectUnsigned(retry.id(),"FIXTURE_COMPLETE");
        for(String sql:List.of("UPDATE futures_policy SET policy_hash='wrong'","DELETE FROM futures_journal","TRUNCATE futures_journal"))
            assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM user_balances WHERE symbol='mKRW'",BigDecimal.class)).isEqualByComparingTo("3000000");
        assertThat(jdbc.queryForObject("SELECT SUM(locked_amount) FROM user_balances",BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM faucet_grants",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trades",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_transactions",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE active_position_id IS NOT NULL",Long.class)).isZero();
    }
}
