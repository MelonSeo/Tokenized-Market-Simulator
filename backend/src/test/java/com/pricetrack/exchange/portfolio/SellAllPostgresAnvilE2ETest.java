package com.pricetrack.exchange.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.reserve.ReserveService;
import com.pricetrack.exchange.tradeaudit.AuditService;
import com.pricetrack.exchange.wallet.UserBalanceRepository;
import com.pricetrack.exchange.market.provider.simulated.SimulatedPriceProvider;
import com.pricetrack.exchange.blockchain.reconciliation.BlockchainReconciliationService;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/** New owned Synthetic PG/Anvil only. No live Google credentials or old acceptance state. */
@EnabledIfEnvironmentVariable(named="SELL_ALL_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"app.google.enabled=false"})
@DirtiesContext
class SellAllPostgresAnvilE2ETest {
    static Properties config;
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry registry) throws Exception {
        config=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("SELL_ALL_E2E_RUN"));
        if(config==null)throw new IllegalStateException("New owned sell-all fixture required");
        config.forEach((k,v)->registry.add(k.toString(),()->v));
    }
    @Autowired AuthService auth;
    @Autowired JwtTokenProvider jwt;
    @Autowired TestRestTemplate http;
    @Autowired UserBalanceRepository balances;
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired SimulatedPriceProvider prices;
    @Autowired BlockchainReconciliationService reconciliation;
    @Autowired ReserveService reserve;
    @Autowired AuditService audit;

    @Test void freshRestAvailableQuantitySellsExactlyToZeroAndAuditsMatch() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users",Integer.class)).isZero();
        var credentials=auth.signup(new AuthController.SignupRequest("sellall_fixture","fixture-password","Sell all fixture"));
        var other=auth.signup(new AuthController.SignupRequest("sellall_other","fixture-password","Other fixture"));
        String token=credentials.accessToken();long user=jwt.getUserId(token);
        reserve.createBaseline(user);
        post("/api/wallet/faucet",Map.of(),token,"sellall-grant-"+UUID.randomUUID(),200);
        prices.tick();
        var buyQuote=post("/api/quotes/buy",Map.of("symbol","mSEC","krwAmount","100000"),token,null,200);
        long buy=post("/api/orders/buy",Map.of("symbol","mSEC","krwAmount","100000","quoteId",buyQuote.path("quoteId").asText()),token,null,202).path("orderId").asLong();
        settle(buy);
        BigDecimal total=balances.findByUserIdAndSymbol(user,"mSEC").orElseThrow().getAmount();
        assertThat(total).isPositive();
        var before=get("/api/portfolio",token);
        assertThat(before.path("tokenAvailableAmount").isTextual()).isTrue();
        assertThat(before.path("tokenAvailableAmount").asText()).isEqualTo(total.toPlainString());
        assertThat(get("/api/portfolio",other.accessToken()).path("tokenAvailableAmount").asText()).isEqualTo("0.000000000000000000");

        // A real pending SELL locks only part of the DB balance until receipt settlement.
        String part=total.divide(BigDecimal.valueOf(2),18,RoundingMode.DOWN).toPlainString();
        long partial=sell(part,token);
        var locked=balances.findByUserIdAndSymbol(user,"mSEC").orElseThrow();
        assertThat(locked.getLockedAmount()).isEqualByComparingTo(part);
        String available=get("/api/portfolio",token).path("tokenAvailableAmount").asText();
        assertThat(available).isEqualTo(locked.getAvailableAmount().toPlainString());
        assertThat(new BigDecimal(available)).isLessThan(total);
        settle(partial);

        // Read again, just like clicking the UI button. No Number or display rounding.
        String exact=get("/api/portfolio",token).path("tokenAvailableAmount").asText();
        long all=sell(exact,token);settle(all);
        assertThat(balances.findByUserIdAndSymbol(user,"mSEC").orElseThrow().getAmount()).isZero();
        assertThat(balances.findByUserIdAndSymbol(user,"mSEC").orElseThrow().getLockedAmount()).isZero();
        assertThat(balances.findByUserIdAndSymbol(user,"mKRW").orElseThrow().getLockedAmount()).isZero();
        assertThat(new BigDecimal(get("/api/portfolio",token).path("tokenAvailableAmount").asText())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trades WHERE user_id=?",Integer.class,user)).isEqualTo(3);
        Web3j chain=Web3j.build(new HttpService(config.getProperty("app.blockchain.rpc-url")));
        try {
            for(long id:List.of(buy,partial,all)) {
                var order=orders.findById(id).orElseThrow();
                assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
                var receipt=chain.ethGetTransactionReceipt(order.getTxHash()).send().getTransactionReceipt().orElseThrow();
                assertThat(receipt.isStatusOK()).isTrue();assertThat(receipt.getLogs()).isNotEmpty();
            }
        } finally { chain.shutdown(); }
        assertThat(reserve.run(user).status()).isEqualTo("MATCH");
        var run=audit.start(user);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(40)).until(()->!audit.detail(run.id()).lifecycle().equals("RUNNING"));
        var result=audit.detail(run.id());assertThat(result.verdict()).isEqualTo("MATCH");
        assertThat(result.coverage().dbComplete()).isTrue();assertThat(result.coverage().chainComplete()).isTrue();
        assertThat(result.coverage().cutStable()).isTrue();assertThat(result.coverage().environmentVerified()).isTrue();
    }
    long sell(String amount,String token) {
        prices.tick();var quote=post("/api/quotes/sell",Map.of("symbol","mSEC","tokenAmount",amount),token,null,200);
        return post("/api/orders/sell",Map.of("symbol","mSEC","tokenAmount",amount,"quoteId",quote.path("quoteId").asText()),token,null,202).path("orderId").asLong();
    }
    void settle(long id) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(()->{
            reconciliation.reconcilePendingTransactions();return orders.findById(id).orElseThrow().getStatus()==OrderStatus.FILLED;
        });
    }
    JsonNode get(String path,String token) {
        var headers=new HttpHeaders();headers.setBearerAuth(token);
        var response=http.exchange(path,HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);return response.getBody();
    }
    JsonNode post(String path,Object body,String token,String key,int expected) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(token);
        if(key!=null)headers.set("Idempotency-Key",key);
        var response=http.exchange(path,HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
        String code=response.getBody()==null?"NO_BODY":response.getBody().path("code").asText();
        if(!code.matches("[A-Z0-9_]*"))code="UNKNOWN";
        assertThat(response.getStatusCode().value()).as(path+" code="+code).isEqualTo(expected);return response.getBody();
    }
}
