package com.pricetrack.exchange.futures.chain;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.auth.AuthController.SignupRequest;
import com.pricetrack.exchange.auth.AuthService;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.runtime.*;
import com.pricetrack.exchange.futures.read.FuturesReadService;
import com.pricetrack.exchange.market.*;
import com.pricetrack.exchange.market.model.*;
import com.pricetrack.exchange.market.provider.MarketPriceProvider;
import com.pricetrack.exchange.wallet.WalletService;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual scheduler -> signed Engine transaction -> canonical settlement. No direct CALL/LIQUIDATE invocation by the test. */
@EnabledIfEnvironmentVariable(named="FUTURES_AUTO_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FuturesAutoPostgresAnvilE2ETest.FixtureMarket.class)
class FuturesAutoPostgresAnvilE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry r)throws Exception {
        var p=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("FUTURES_AUTO_E2E_RUN"));
        if(p==null || !p.getProperty("app.futures.runtime-enabled","").equals("true"))throw new IllegalStateException("NEW_AUTO_FIXTURE_REQUIRED");
        p.forEach((k,v)->r.add(k.toString(),()->v));
    }
    @TestConfiguration static class FixtureMarket {
        @Bean @Primary Scenario scenario(DataSource ds,PlatformTransactionManager tx,MarketWebSocketPublisher events){return new Scenario(ds,tx,events);}
    }
    /** Test-only persistent synthetic state; no product API permits price control. One committed fresh tick per second. */
    static class Scenario implements MarketPriceProvider,AutoCloseable {
        final JdbcTemplate jdbc;final TransactionTemplate tx;final MarketWebSocketPublisher events;
        final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
        volatile MarketPriceSnapshot snapshot;volatile String condition="OPEN";
        Scenario(DataSource ds,PlatformTransactionManager manager,MarketWebSocketPublisher events) {
            jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(manager);this.events=events;
            jdbc.execute("CREATE TABLE IF NOT EXISTS futures_auto_prices(id INT PRIMARY KEY,price NUMERIC(20,8),sequence BIGINT NOT NULL)");
            jdbc.update("INSERT INTO futures_auto_prices SELECT 1,75000,0 WHERE NOT EXISTS(SELECT 1 FROM futures_auto_prices WHERE id=1)");tick();
            scheduler.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
        }
        synchronized void price(String price){jdbc.update("UPDATE futures_auto_prices SET price=? WHERE id=1",new BigDecimal(price));tick();}
        synchronized void tick() {
            var value=tx.execute(s->{jdbc.update("UPDATE futures_auto_prices SET sequence=sequence+1 WHERE id=1");return jdbc.queryForObject("SELECT price FROM futures_auto_prices WHERE id=1",BigDecimal.class);});
            Instant now=Instant.now();snapshot=new MarketPriceSnapshot("mSEC",value,new BigDecimal("75000"),BigDecimal.ZERO,BigDecimal.ZERO,
                condition.equals("CLOSED")?MarketStatus.CLOSED:MarketStatus.OPEN,condition.equals("STALE")?PriceStatus.STALE:PriceStatus.SIMULATED,"SIMULATED",now);
            events.publishPrice(snapshot,BigDecimal.ONE); // transaction above has COMMITTED
        }
        public MarketPriceSnapshot current(){return snapshot;}
        @Override public void close(){scheduler.shutdownNow();}
    }
    @Autowired FuturesLedger ledger;@Autowired FuturesTransactionStore store;@Autowired FuturesRpcGateway rpc;
    @Autowired FuturesService commands;@Autowired FuturesReadService reads;@Autowired FuturesWorker worker;
    @Autowired AuthService auth;@Autowired WalletService wallets;@Autowired JdbcTemplate jdbc;@Autowired Scenario scenario;
    final BigInteger c=BigInteger.TEN.pow(23);
    String key(){return UUID.randomUUID().toString();}
    long user(String name){auth.signup(new SignupRequest(name,"fixture-password",name));long id=jdbc.queryForObject("SELECT id FROM users WHERE login_id=?",Long.class,name);wallets.faucet(id,key());return id;}
    void await(String code,int seconds,BooleanSupplier predicate)throws Exception {
        long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!predicate.getAsBoolean()){if(System.nanoTime()>stop)throw new IllegalStateException(code);Thread.sleep(100);}
    }
    String open(long uid,Side side) throws Exception {
        var preview=commands.preview(uid,Action.OPEN,null,0,side,10,c);var op=commands.consume(uid,preview.id(),key(),Action.OPEN,null,0);
        await("AUTO_OPEN_TIMEOUT",20,()->ledger.position(op.positionId(),uid).status().equals("OPEN"));return op.positionId();
    }
    void close(long uid,String pid)throws Exception {var p=ledger.position(pid,uid);var v=commands.preview(uid,Action.CLOSE,pid,p.version(),null,0,BigInteger.ZERO);commands.consume(uid,v.id(),key(),Action.CLOSE,pid,p.version());await("AUTO_CLOSE_TIMEOUT",20,()->ledger.position(pid,uid).status().equals("CLOSED"));}
    void evidence(String name,Map<String,?> value)throws Exception {
        var folder=java.nio.file.Path.of(System.getenv("FUTURES_AUTO_E2E_RUN")).toRealPath();java.nio.file.Files.writeString(folder.resolve(name+".evidence.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value),java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    @Test void automaticLongShortGraceRecoveryTopupAndSafeMarketPause()throws Exception {
        // No NTP/timestamp changes. A naturally advancing chain must be aligned before capital or users are created.
        await("AUTO_CLOCK_BLOCKED",25,()->{try{return rpc.block(rpc.height()).getTimestamp().longValueExact()<=Instant.now().getEpochSecond()+1;}catch(Exception e){return false;}});
        ledger.initializeEmptyFixture();
        for(var side:Side.values()) {
            scenario.condition="OPEN";scenario.price("75000");long uid=user("auto_"+side.name().toLowerCase(Locale.ROOT));String pid=open(uid,side);
            long triggeredAt=System.nanoTime();scenario.price(side==Side.LONG?"60000":"90000");
            await("AUTO_CALL_TIMEOUT",25,()->ledger.position(pid,uid).riskStatus().equals("GRACE"));
            long confirmedAt=System.nanoTime();long deadline=ledger.position(pid,uid).deadline();
            assertThat(deadline).isPositive();scenario.condition="CLOSED";scenario.tick();
            // Explicit closure cannot produce any additional risk operation; existing signed work still reconciles.
            long count=jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE position_id=?",Long.class,pid);
            Thread.sleep(2100);assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE position_id=?",Long.class,pid)).isEqualTo(count);
            scenario.condition="STALE";scenario.tick();Thread.sleep(1100);
            assertThat(ledger.position(pid,uid).status()).isEqualTo("OPEN");
            // Resume using freshly committed ticks. The server worker, not this test, performs liquidation after chain deadline.
            scenario.condition="OPEN";scenario.tick();
            await("AUTO_LIQUIDATION_TIMEOUT",85,()->ledger.position(pid,uid).status().equals("LIQUIDATED"));
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-confirmedAt);
            var tx=jdbc.queryForMap("SELECT t.tx_hash,t.block_number,t.block_hash,o.observed_at FROM futures_transactions t JOIN futures_operations o ON o.id=t.operation_id WHERE o.position_id=? AND o.action='LIQUIDATE' AND t.status='CONFIRMED'",pid);
            var receipt=rpc.receipt((String)tx.get("tx_hash")).orElseThrow();long blockTime=rpc.block(receipt.getBlockNumber()).getTimestamp().longValueExact();
            assertThat(receipt.isStatusOK()).isTrue();assertThat(receipt.getLogs()).hasSize(2);assertThat(blockTime).isGreaterThanOrEqualTo(deadline);assertThat(((Number)tx.get("observed_at")).longValue()).isGreaterThanOrEqualTo(deadline);
            // The call's canonical block is the grace origin, not when a polling observer happens to notice GRACE.
            long triggerElapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-triggeredAt);
            var call=jdbc.queryForMap("SELECT t.block_number FROM futures_transactions t JOIN futures_operations o ON o.id=t.operation_id WHERE o.position_id=? AND o.action='START_CALL' AND t.status='CONFIRMED'",pid);
            long callBlockTime=rpc.block(BigInteger.valueOf(((Number)call.get("block_number")).longValue())).getTimestamp().longValueExact();
            assertThat(deadline-callBlockTime).isEqualTo(60);assertThat(blockTime-callBlockTime).isGreaterThanOrEqualTo(60);
            assertThat(triggerElapsed).isGreaterThanOrEqualTo(60000);
            evidence("auto-liquidation-"+side.name().toLowerCase(Locale.ROOT),Map.of("deadline",deadline,"callBlockTimestamp",callBlockTime,"receiptTimestamp",blockTime,"observedAt",tx.get("observed_at"),"txHash",tx.get("tx_hash"),"elapsedAfterObservedGraceMillis",elapsed,"elapsedSinceRiskTriggerMillis",triggerElapsed,"receiptVerified",true));
            worker.tick();assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE position_id=? AND action='LIQUIDATE' AND state='CONFIRMED'",Long.class,pid)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,uid)).isZero();
        }
        scenario.price("75000");long recovery=user("auto_recovery");String recovered=open(recovery,Side.LONG);scenario.price("70000");
        await("AUTO_RECOVERY_CALL_TIMEOUT",25,()->ledger.position(recovered,recovery).riskStatus().equals("GRACE"));scenario.price("74000");
        await("AUTO_RECOVERY_CLEAR_TIMEOUT",25,()->ledger.position(recovered,recovery).riskStatus().equals("HEALTHY"));close(recovery,recovered);
        scenario.price("75000");long topup=user("auto_topup");String topped=open(topup,Side.SHORT);scenario.price("80000");
        await("AUTO_TOPUP_CALL_TIMEOUT",25,()->ledger.position(topped,topup).riskStatus().equals("GRACE"));
        var p=ledger.position(topped,topup);commands.margin(topup,topped,key(),p.version(),c);
        await("AUTO_TOPUP_TIMEOUT",25,()->ledger.position(topped,topup).riskStatus().equals("HEALTHY"));close(topup,topped);
        assertThat(jdbc.queryForObject("SELECT SUM(locked_amount) FROM user_balances",BigDecimal.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(wallet_delta+house_delta) FROM futures_journal",BigDecimal.class)).isZero();
        var h=jdbc.queryForObject("SELECT amount FROM futures_house WHERE id=1",BigDecimal.class);
        var d=jdbc.queryForObject("SELECT COALESCE(SUM(house_delta),0) FROM futures_journal",BigDecimal.class);assertThat(h).isEqualByComparingTo(new BigDecimal(FuturesLedger.INITIAL_CAPITAL).add(d));
    }
}
