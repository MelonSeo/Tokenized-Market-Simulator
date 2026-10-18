package com.pricetrack.exchange.futures.ledger;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.auth.AuthController.SignupRequest;
import com.pricetrack.exchange.auth.AuthService;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.market.provider.simulated.SimulatedPriceProvider;
import com.pricetrack.exchange.order.OrderService;
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

/** Real PG DB_ONLY spot/faucet/margin contention, not an onchain futures settlement test. */
@EnabledIfEnvironmentVariable(named="FUTURES_DB_ONLY_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class FuturesSpotPostgresE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry r)throws Exception {
        var p=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("FUTURES_DB_ONLY_E2E_RUN"));
        if(p==null || !p.getProperty("app.futures.enabled","").equals("true")) throw new IllegalStateException("New guarded futures DB-only fixture required");
        p.setProperty("app.blockchain.enabled","false"); // only this test's runtime, no existing settings changed
        p.setProperty("app.blockchain.price-report.enabled","false");
        p.forEach((k,v)->r.add(k.toString(),()->v));
    }
    @Autowired FuturesLedger ledger; @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth; @Autowired WalletService wallets;
    @Autowired OrderService spot; @Autowired SimulatedPriceProvider prices;
    static final BigInteger C=new BigInteger("100000000000000000000000"),P=new BigInteger("7500000000000");
    String key() {return UUID.randomUUID().toString();}
    long user(String name,boolean faucet) {
        auth.signup(new SignupRequest(name,"fixture-only-password",name));
        long uid=jdbc.queryForObject("SELECT id FROM users WHERE login_id=?",Long.class,name);
        if(faucet) wallets.faucet(uid,key());return uid;
    }
    record Attempt(String operation,boolean success) {}
    List<Attempt> race(Callable<Attempt> a,Callable<Attempt> b)throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var futures=new ArrayList<Future<Attempt>>();
            for(var work:List.of(a,b))futures.add(pool.submit(()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("barrier");return work.call();}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            return List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS));
        }finally{start.countDown();pool.shutdownNow();}
    }
    Attempt future(long uid,BigInteger margin) {
        try{return new Attempt(ledger.prepareOpen(uid,key(),Side.LONG,margin,10,P,1000).id(),true);}
        catch(FuturesLedger.Failure e){assertThat(e.code).isEqualTo("INSUFFICIENT_AVAILABLE_MARGIN");return new Attempt(null,false);}
    }
    BigDecimal balance(long uid,String field){return jdbc.queryForObject("SELECT "+field+" FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,uid);}
    @Test void actualPostgresSharedBalanceCannotSpendFuturesCollateral()throws Exception {
        ledger.initializeEmptyFixture();
        long protectedUser=user("futures_protected",true);
        var protectedOp=ledger.prepareOpen(protectedUser,key(),Side.LONG,C,10,P,1000);
        prices.tick();
        assertThatThrownBy(()->spot.buy(protectedUser,"mSEC",new BigDecimal("950000"),null))
            .isInstanceOf(com.pricetrack.exchange.common.exception.InsufficientBalanceException.class);
        assertThat(balance(protectedUser,"amount")).isEqualByComparingTo("1000000");
        assertThat(balance(protectedUser,"locked_amount")).isEqualByComparingTo("100000");
        ledger.rejectUnsigned(protectedOp.id(),"FIXTURE_COMPLETE");

        long competing=user("futures_spot_race",true); prices.tick();
        var outcomes=race(()->future(competing,C.multiply(BigInteger.valueOf(7))),()->{
            try{spot.buy(competing,"mSEC",new BigDecimal("700000"),null);return new Attempt(null,true);}
            catch(com.pricetrack.exchange.common.exception.InsufficientBalanceException expected){return new Attempt(null,false);}
        });
        assertThat(outcomes.stream().filter(Attempt::success)).hasSize(1);
        assertThat(balance(competing,"amount")).isGreaterThanOrEqualTo(balance(competing,"locked_amount"));
        if(outcomes.get(0).operation()!=null)ledger.rejectUnsigned(outcomes.get(0).operation(),"FIXTURE_COMPLETE");
        assertThat(balance(competing,"locked_amount")).isEqualByComparingTo("0");

        long creditRace=user("futures_credit_race",false);
        var credited=race(()->future(creditRace,C),()->{wallets.faucet(creditRace,key());return new Attempt(null,true);});
        assertThat(credited.get(1).success()).isTrue();
        assertThat(balance(creditRace,"amount")).isEqualByComparingTo("1000000");
        if(credited.get(0).operation()!=null)ledger.rejectUnsigned(credited.get(0).operation(),"FIXTURE_COMPLETE");
        assertThat(balance(creditRace,"locked_amount")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM faucet_grants",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_transactions",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_balances WHERE amount<locked_amount OR locked_amount<0",Long.class)).isZero();
    }
}
