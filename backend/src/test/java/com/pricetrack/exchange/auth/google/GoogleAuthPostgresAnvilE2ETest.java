package com.pricetrack.exchange.auth.google;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.wallet.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.reserve.*;
import com.pricetrack.exchange.tradeaudit.AuditService;
import com.pricetrack.exchange.market.provider.simulated.SimulatedPriceProvider;
import com.pricetrack.exchange.blockchain.reconciliation.BlockchainReconciliationService;

/** New owned Phase A fixture only: real PG locking/unique constraints and real Anvil signed settlement. */
@EnabledIfEnvironmentVariable(named="GOOGLE_AUTH_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"app.google.enabled=true","app.google.client-id=offline-google-client",
            "app.google.allowed-origins=http://localhost:5173"})
@Import(GoogleTestIdentity.class) @DirtiesContext
class GoogleAuthPostgresAnvilE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry registry) throws Exception {
        Properties config = com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("GOOGLE_AUTH_E2E_RUN"));
        if (config == null) throw new IllegalStateException("New owned Google fixture required");
        config.forEach((k,v)->registry.add(k.toString(),()->v));
    }
    @Autowired GoogleAccountService google;
    @Autowired UserRepository users;
    @Autowired AuthService local;
    @Autowired JwtTokenProvider jwt;
    @SpyBean WalletService wallets;
    @Autowired UserBalanceRepository balances;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource ds;
    @Autowired TestRestTemplate http;
    @Autowired ReserveService reserve;
    @Autowired AuditService audit;
    @Autowired SimulatedPriceProvider prices;
    @Autowired OrderRepository orders;
    @Autowired BlockchainReconciliationService reconciliation;

    @Test void additiveMigrationAtomicIdentityRacesAndGoogleOnlyTrading() throws Exception {
        // Repeat the real additive SQL, never drop a table or reset a journal.
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users",Integer.class)).isZero();

        String sub="same-sub-race";
        String binding1=GoogleAccountService.random(),binding2=GoogleAccountService.random();
        var first=google.issue(binding1,null);var second=google.issue(binding2,null);
        var race=parallel(()->google.login(first.challengeId(),binding1,GoogleTestIdentity.token(sub,first.nonce())),
                ()->google.login(second.challengeId(),binding2,GoogleTestIdentity.token(sub,second.nonce())));
        assertThat(race).noneMatch(Throwable.class::isInstance);
        Long id=jwt.getUserId(((AuthController.TokenResponse)race.getFirst()).accessToken());
        assertThat(jwt.getUserId(((AuthController.TokenResponse)race.get(1)).accessToken())).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE google_sub=?",Integer.class,sub)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_balances WHERE user_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(users.findById(id).orElseThrow().getRole()).isEqualTo(UserRole.USER);
        assertThat(users.findById(id).orElseThrow().getEmail()).isNull();

        String oneBinding=GoogleAccountService.random();var once=google.issue(oneBinding,null);
        String oneToken=GoogleTestIdentity.token("once-sub",once.nonce());
        var duplicate=parallel(()->google.login(once.challengeId(),oneBinding,oneToken),()->google.login(once.challengeId(),oneBinding,oneToken));
        assertThat(duplicate.stream().filter(GoogleAuthFailure.class::isInstance).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE google_sub='once-sub'",Integer.class)).isEqualTo(1);

        String rollbackBinding=GoogleAccountService.random();var rollback=google.issue(rollbackBinding,null);
        doThrow(new IllegalStateException("fixture rollback")).when(wallets).initializeBalances(anyLong());
        try { assertThatThrownBy(()->google.login(rollback.challengeId(),rollbackBinding,GoogleTestIdentity.token("rollback-sub",rollback.nonce())))
                .isInstanceOf(IllegalStateException.class); }
        finally { doCallRealMethod().when(wallets).initializeBalances(anyLong()); }
        assertThat(users.findByGoogleSub("rollback-sub")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM google_auth_challenges WHERE id=?",Timestamp.class,rollback.challengeId())).isNull();
        google.login(rollback.challengeId(),rollbackBinding,GoogleTestIdentity.token("rollback-sub",rollback.nonce()));

        var local1=local.signup(new AuthController.SignupRequest("link_fixture_a","fixture-password","Link fixture"));
        var local2=local.signup(new AuthController.SignupRequest("link_fixture_b","fixture-password","Link fixture"));
        Long a=jwt.getUserId(local1.accessToken()),b=jwt.getUserId(local2.accessToken());
        String ab=GoogleAccountService.random(),bb=GoogleAccountService.random();
        var ac=google.issue(ab,a);var bc=google.issue(bb,b);
        var competing=parallel(()->{google.link(a,ac.challengeId(),ab,GoogleTestIdentity.token("link-race-sub",ac.nonce()),"fixture-password");return "OK";},
                ()->{google.link(b,bc.challengeId(),bb,GoogleTestIdentity.token("link-race-sub",bc.nonce()),"fixture-password");return "OK";});
        assertThat(competing.stream().filter(x->x instanceof GoogleAuthFailure failure && failure.status()==HttpStatus.CONFLICT).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE google_sub='link-race-sub'",Integer.class)).isEqualTo(1);

        // Baseline BEFORE any user credit/trade. No operator/user balance equality assumption.
        reserve.createBaseline(a);
        String token=((AuthController.TokenResponse)race.getFirst()).accessToken();
        post("/api/wallet/faucet",Map.of(),token,"google-e2e-first-grant",200);
        prices.tick();var quote=post("/api/quotes/buy",Map.of("symbol","mSEC","krwAmount","100000"),token,null,200);
        long buy=post("/api/orders/buy",Map.of("symbol","mSEC","krwAmount","100000","quoteId",quote.path("quoteId").asText()),token,null,202).path("orderId").asLong();
        settle(buy);
        var qty=balances.findByUserIdAndSymbol(id,"mSEC").orElseThrow().getAmount();
        prices.tick();var sellQuote=post("/api/quotes/sell",Map.of("symbol","mSEC","tokenAmount",qty.toPlainString()),token,null,200);
        long sell=post("/api/orders/sell",Map.of("symbol","mSEC","tokenAmount",qty.toPlainString(),"quoteId",sellQuote.path("quoteId").asText()),token,null,202).path("orderId").asLong();
        settle(sell);
        assertThat(balances.findByUserIdAndSymbol(id,"mSEC").orElseThrow().getAmount()).isZero();
        assertThat(balances.findByUserIdAndSymbol(id,"mKRW").orElseThrow().getLockedAmount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trades WHERE user_id=?",Integer.class,id)).isEqualTo(2);
        get("/api/portfolio",token,200);get("/api/me",token,200);
        get("/api/orders/"+buy,local1.accessToken(),404);
        get("/api/admin/faucet",token,403);
        // Linking a funded local account must preserve locks, orders, trades and faucet rows byte-for-value.
        wallets.faucet(a,"local-link-history-grant");
        prices.tick();var historicalQuote=post("/api/quotes/buy",Map.of("symbol","mSEC","krwAmount","10000"),local1.accessToken(),null,200);
        long historical=post("/api/orders/buy",Map.of("symbol","mSEC","krwAmount","10000","quoteId",historicalQuote.path("quoteId").asText()),local1.accessToken(),null,202).path("orderId").asLong();
        settle(historical);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trades WHERE user_id=?",Integer.class,a)).isEqualTo(1);
        prices.tick();var pendingQuote=post("/api/quotes/buy",Map.of("symbol","mSEC","krwAmount","10000"),local1.accessToken(),null,200);
        long pending=post("/api/orders/buy",Map.of("symbol","mSEC","krwAmount","10000","quoteId",pendingQuote.path("quoteId").asText()),local1.accessToken(),null,202).path("orderId").asLong();
        var before=snapshot(a);
        // One of a/b may already own the competing sub. Use the winner's identical-sub proof, or a new identity if empty.
        String target=users.findById(a).orElseThrow().getGoogleSub();if(target==null)target="preserve-sub";
        var preserve=google.issue(ab,a);google.link(a,preserve.challengeId(),ab,GoogleTestIdentity.token(target,preserve.nonce()),"fixture-password");
        assertThat(snapshot(a)).isEqualTo(before);
        var again=google.issue(ab,null);assertThat(jwt.getUserId(google.login(again.challengeId(),ab,GoogleTestIdentity.token(target,again.nonce())).accessToken())).isEqualTo(a);
        assertThat(snapshot(a)).isEqualTo(before);
        get("/api/me",local1.accessToken(),200);get("/api/portfolio",local1.accessToken(),200);
        settle(pending);
        assertThat(reserve.run(a).status()).isEqualTo("MATCH");
        var run=audit.start(a);org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(40)).until(()->!audit.detail(run.id()).lifecycle().equals("RUNNING"));
        assertThat(audit.detail(run.id()).verdict()).isEqualTo("MATCH");
    }
    Map<String,Object> snapshot(Long id) {
        Map<String,Object> snapshot=new LinkedHashMap<>();
        for(String table:List.of("user_balances","orders","trades","faucet_grants")) snapshot.put(table,jdbc.queryForList("SELECT * FROM "+table+" WHERE user_id=? ORDER BY id",id));
        snapshot.put("userId",users.findById(id).orElseThrow().getId());snapshot.put("role",users.findById(id).orElseThrow().getRole());return snapshot;
    }
    List<Object> parallel(Callable<?> one,Callable<?> two)throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();try{return one.call();}catch(Exception failure){return failure;}});
            var b=pool.submit(()->{start.await();try{return two.call();}catch(Exception failure){return failure;}});
            start.countDown();return List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    void settle(long id)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<deadline){reconciliation.reconcilePendingTransactions();if(orders.findById(id).orElseThrow().getStatus()==OrderStatus.FILLED)return;Thread.sleep(50);}
        throw new AssertionError("Google fixture order not FILLED");
    }
    JsonNode post(String path,Object body,String token,String key,int expected) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(token);if(key!=null)headers.set("Idempotency-Key",key);
        var response=http.exchange(path,HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
        String code=response.getBody()==null?"NO_BODY":response.getBody().path("code").asText();
        if(!code.matches("[A-Z0-9_]*"))code="UNKNOWN";
        assertThat(response.getStatusCode().value()).as(path+" publicCode="+code).isEqualTo(expected);return response.getBody();
    }
    void get(String path,String token,int expected) {
        var headers=new HttpHeaders();headers.setBearerAuth(token);
        assertThat(http.exchange(path,HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getStatusCode().value()).as(path).isEqualTo(expected);
    }
}
