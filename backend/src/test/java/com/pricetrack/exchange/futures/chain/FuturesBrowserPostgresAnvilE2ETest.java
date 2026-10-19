package com.pricetrack.exchange.futures.chain;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.auth.AuthController.SignupRequest;
import com.pricetrack.exchange.auth.AuthService;
import com.pricetrack.exchange.futures.audit.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.market.provider.simulated.SimulatedPriceProvider;
import com.pricetrack.exchange.reserve.ReserveService;
import java.math.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.web.server.ResponseStatusException;

/** Product SimulatedPriceProvider/engine/candle commit/WS, no @Primary fake price provider and no test price API. */
@EnabledIfEnvironmentVariable(named="FUTURES_BROWSER_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class FuturesBrowserPostgresAnvilE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry r)throws Exception {
        var p=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("FUTURES_BROWSER_E2E_RUN"));if(p==null)throw new IllegalStateException("NEW_PRODUCT_BROWSER_FIXTURE_REQUIRED");p.forEach((k,v)->r.add(k.toString(),()->v));
        r.add("app.price.initial-delay-ms",()->"1000");r.add("app.price.update-interval-ms",()->"1000");
    }
    @LocalServerPort int port;@Autowired SimulatedPriceProvider provider;@Autowired FuturesLedger ledger;@Autowired FuturesRpcGateway rpc;
    @Autowired AuthService auth;@Autowired JdbcTemplate jdbc;@Autowired FuturesAuditService audits;@Autowired ReserveService reserves;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    int baselineCutRetries;
    <T>T freshBaseline(java.util.function.Supplier<T> create,String retryable)throws Exception {
        for(int n=0;n<4;n++)try{return create.get();}catch(ResponseStatusException moving){if(!retryable.equals(moving.getReason())||n==3)throw moving;baselineCutRetries++;Thread.sleep(100);}
        throw new IllegalStateException("BASELINE_UNAVAILABLE");
    }
    record Owned(ProcessHandle handle,Instant start) {
        boolean sameAlive(){return handle.isAlive()&&handle.info().startInstant().filter(start::equals).isPresent();}
    }
    void capture(Process process,Map<Long,Owned> owned){process.toHandle().descendants().forEach(h->h.info().startInstant().ifPresent(at->owned.putIfAbsent(h.pid(),new Owned(h,at))));}
    void cleanup(Process process,Map<Long,Owned> owned,Path run)throws Exception {
        capture(process,owned);
        if(process.isAlive()){process.destroy();process.waitFor(2,TimeUnit.SECONDS);}
        owned.values().stream().filter(Owned::sameAlive).forEach(p->p.handle().destroy());
        long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(owned.values().stream().anyMatch(Owned::sameAlive)&&System.nanoTime()<stop)Thread.sleep(50);
        owned.values().stream().filter(Owned::sameAlive).forEach(p->p.handle().destroyForcibly());
        if(process.isAlive())process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);
        stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(owned.values().stream().anyMatch(Owned::sameAlive)&&System.nanoTime()<stop)Thread.sleep(50);
        long alive=owned.values().stream().filter(Owned::sameAlive).count();
        Files.writeString(run.resolve("owned-process-cleanup.evidence.json"),json.writeValueAsString(Map.of("scope","ONLY_NEW_NODE_DESCENDANTS_WITH_MATCHING_START_TIME","tracked",owned.size(),"aliveAfterCleanup",alive,"nodeAlive",process.isAlive())),StandardOpenOption.CREATE_NEW);
        assertThat(alive).as("Only own verified descendants may be terminated").isZero();assertThat(process.isAlive()).isFalse();
    }
    @Test void productSyntheticChromeLongShortMarginGraceRecoveryLiquidationAndAudit()throws Exception {
        Path run=Path.of(System.getenv("FUTURES_BROWSER_E2E_RUN")).toRealPath(),repo=Path.of("..").toRealPath();
        long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(rpc.block(rpc.height()).getTimestamp().longValueExact()>Instant.now().getEpochSecond()+1){if(System.nanoTime()>stop)throw new IllegalStateException("BROWSER_CLOCK_BLOCKED");Thread.sleep(100);}
        ledger.initializeEmptyFixture();freshBaseline(()->reserves.createBaseline(1),"BASELINE_CUT_NOT_STABLE");var baseline=freshBaseline(()->audits.baseline(1),"FUTURES_BASELINE_CUT_CHANGED");
        String password=UUID.randomUUID().toString();auth.signup(new SignupRequest("chrome_future_owner",password,"선물 브라우저 사용자"));
        Files.writeString(run.resolve("secrets/browser-account.json"),json.writeValueAsString(Map.of("loginId","chrome_future_owner","password",password)),StandardOpenOption.CREATE_NEW);
        auth.signup(new SignupRequest("chrome_future_admin",password,"선물 감사 관리자"));
        jdbc.update("UPDATE users SET role='ADMIN' WHERE login_id='chrome_future_admin'");
        Files.writeString(run.resolve("secrets/browser-admin-account.json"),json.writeValueAsString(Map.of("loginId","chrome_future_admin","password",password)),StandardOpenOption.CREATE_NEW);
        var process=new ProcessBuilder("node",repo.resolve("deployment/futures-browser.mjs").toString(),run.toString(),Integer.toString(port)).directory(repo.toFile()).redirectErrorStream(true).redirectOutput(run.resolve("browser-driver.private.log").toFile()).start();
        var commands=new ArrayList<Map<String,Object>>();var owned=new HashMap<Long,Owned>();int sequence=1;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(480);
        Throwable primary=null;
        try {
            while(process.isAlive()){
                capture(process,owned);
                if(System.nanoTime()>deadline)throw new IllegalStateException("BROWSER_DEADLINE");
                Path command=run.resolve("browser-control-"+sequence+".json");
                if(Files.exists(command)){
                    if(Files.size(command)>256)throw new IllegalStateException("INVALID_PRIVATE_CONTROL");var value=json.readTree(command.toFile());if(value.path("sequence").asInt()!=sequence)throw new IllegalStateException("CONTROL_SEQUENCE");
                    String label=value.path("label").asText();String target=switch(label){case "BASE"->"75000";case "LONG_CRASH"->"60000";case "SHORT_CRASH"->"90000";case "LONG_CALL"->"70000";case "LONG_RECOVER"->"74000";case "SHORT_CALL"->"80000";default->throw new IllegalStateException("INVALID_SCENARIO_LABEL");};
                    // Only this NEW fixture checkpoint is seeded. The product provider then generates, records and commits
                    // a real engine tick/candles before exposing its snapshot and publishing the public price event.
                    // No fake MarketPriceProvider, event injection, chain warp or ordinary-user control endpoint.
                    synchronized(provider){jdbc.update("UPDATE synthetic_market_state SET price=?,trend=0 WHERE symbol='mSEC'",new BigDecimal(target));provider.tick();}
                    var mark=provider.current();var saved=jdbc.queryForMap("SELECT price,step,observed_at FROM synthetic_market_state WHERE symbol='mSEC'");assertThat(mark.provider()).isEqualTo("SIMULATED");assertThat(mark.price()).isEqualByComparingTo((BigDecimal)saved.get("price"));
                    var proof=Map.<String,Object>of("sequence",sequence,"label",label,"committed",true,"price",mark.price().toPlainString(),"observedAt",mark.observedAt().toString(),"step",saved.get("step"));commands.add(proof);Files.writeString(run.resolve("browser-control-"+sequence+".ack.json"),json.writeValueAsString(proof),StandardOpenOption.CREATE_NEW);sequence++;
                }
                Thread.sleep(50);
            }
            assertThat(process.exitValue()).as("Inspect private browser evidence; no reset/retry of failed fixture").isZero();
        }catch(Exception|AssertionError failure){primary=failure;throw failure;}
        finally{try{cleanup(process,owned,run);}catch(Exception|AssertionError failure){if(primary!=null)primary.addSuppressed(failure);else throw failure;}}
        var browser=json.readTree(run.resolve("browser/result.json").toFile());assertThat(browser.path("status").asText()).isEqualTo("PASS");assertThat(browser.path("events").size()).isPositive();
        var ui=browser.path("adminAuditUi");var uiAudit=audits.detail(ui.path("id").asText());
        assertThat(ui.path("status").asText()).isEqualTo(uiAudit.status());
        assertThat(ui.path("complete").asBoolean()).isEqualTo(uiAudit.coverage().complete());
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id=?",String.class,ui.path("userId").asLong())).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_positions WHERE status='LIQUIDATED'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(locked_amount) FROM user_balances",BigDecimal.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(wallet_delta+house_delta) FROM futures_journal",BigDecimal.class)).isZero();
        var liquidation=new ArrayList<Map<String,Object>>();
        for(var row:jdbc.queryForList("SELECT o.position_id,o.observed_at,t.tx_hash,t.block_number FROM futures_operations o JOIN futures_transactions t ON t.operation_id=o.id WHERE o.action='LIQUIDATE' AND o.state='CONFIRMED'")){
            String pid=(String)row.get("position_id");var call=jdbc.queryForMap("SELECT t.block_number FROM futures_transactions t JOIN futures_operations o ON o.id=t.operation_id WHERE o.position_id=? AND o.action='START_CALL' AND o.state='CONFIRMED'",pid);long start=rpc.block(BigInteger.valueOf(((Number)call.get("block_number")).longValue())).getTimestamp().longValueExact();var receipt=rpc.receipt((String)row.get("tx_hash")).orElseThrow();long end=rpc.block(receipt.getBlockNumber()).getTimestamp().longValueExact();assertThat(receipt.isStatusOK()).isTrue();assertThat(receipt.getLogs()).hasSize(2);assertThat(rpc.block(receipt.getBlockNumber()).getHash()).isEqualTo(receipt.getBlockHash());assertThat(end-start).isGreaterThanOrEqualTo(60);assertThat(((Number)row.get("observed_at")).longValue()).isGreaterThanOrEqualTo(start+60);liquidation.add(Map.of("positionId",pid,"callTimestamp",start,"liquidationTimestamp",end,"txHash",row.get("tx_hash")));
        }
        FuturesAuditModels.Result audit=null;for(int n=0;n<4;n++){audit=audits.run(1);if(!audit.reason().equals("CUT_CHANGED"))break;Thread.sleep(100);}assertThat(audit.status()).as(audit.reason()).isEqualTo("MATCH");assertThat(audit.coverage().complete()).isTrue();assertThat(storeBaseline()).isEqualTo(baseline);
        var reserve=reserves.run(1);assertThat(reserve.status()).isEqualTo("MATCH");
        Files.writeString(run.resolve("product-browser.evidence.json"),json.writeValueAsString(Map.of("productProvider",provider.getClass().getSimpleName(),"privateCheckpointScenarioControls",commands,"liquidations",liquidation,"audit",audit,"reserve",reserve,"actualChrome",true,"paidAi",false,"uncommittedBaselineCutRetries",baselineCutRetries)),StandardOpenOption.CREATE_NEW);
    }
    @Autowired FuturesAuditStore store;
    FuturesAuditModels.Baseline storeBaseline(){return store.baseline().orElseThrow();}
}
