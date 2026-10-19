package com.pricetrack.exchange.futures.chain;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.websocket.event.*;
import com.pricetrack.exchange.websocket.publisher.WebSocketEventPublisher;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** Actual HTTP + private STOMP -> worker -> local Anvil -> PostgreSQL. Google identity below is an offline null-loginId fixture. */
@EnabledIfEnvironmentVariable(named="FUTURES_API_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FuturesAutoPostgresAnvilE2ETest.FixtureMarket.class)
class FuturesApiPostgresAnvilE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry r)throws Exception {
        var p=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("FUTURES_API_E2E_RUN"));if(p==null)throw new IllegalStateException("NEW_API_FIXTURE_REQUIRED");p.forEach((k,v)->r.add(k.toString(),()->v));
    }
    @LocalServerPort int port;@Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired FuturesLedger ledger;@Autowired FuturesRpcGateway rpc;@Autowired JwtTokenProvider jwt;@Autowired WebSocketEventPublisher events;@Autowired PlatformTransactionManager manager;
    record Client(WebSocketStompClient client,StompSession session,BlockingQueue<JsonNode> messages) implements AutoCloseable {public void close(){session.disconnect();client.stop();}}
    ResponseEntity<JsonNode> call(HttpMethod method,String path,String token,Object body,String key) {
        var headers=new HttpHeaders();if(token!=null)headers.setBearerAuth(token);if(key!=null)headers.set("Idempotency-Key",key);headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
    String signup(String login) {var response=call(HttpMethod.POST,"/api/auth/signup",null,new AuthController.SignupRequest(login,"fixture-password",login),null);assertThat(response.getStatusCode().value()).isEqualTo(201);return response.getBody().path("accessToken").asText();}
    Client socket(long uid,String token)throws Exception {
        var client=new WebSocketStompClient(new StandardWebSocketClient());
        var converter=new MappingJackson2MessageConverter();converter.setObjectMapper(json);client.setMessageConverter(converter);
        var headers=new StompHeaders();headers.set("Authorization","Bearer "+token);
        var session=client.connectAsync("ws://127.0.0.1:"+port+"/ws",new WebSocketHttpHeaders(),headers,new StompSessionHandlerAdapter(){}).get(8,TimeUnit.SECONDS);
        var messages=new LinkedBlockingQueue<JsonNode>();session.subscribe(WebSocketDestinations.FUTURES_SUBSCRIPTION,new StompFrameHandler(){
            public Type getPayloadType(StompHeaders h){return JsonNode.class;}public void handleFrame(StompHeaders h,Object p){messages.add((JsonNode)p);}
        });
        // Readiness probe only; actual ledger events below are distinct and originate inside DB transactions.
        long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean ready=false;
        while(System.nanoTime()<stop){events.publishToUser(uid,WebSocketDestinations.FUTURES_QUEUE,WebSocketEventType.FUTURES_UPDATED,Map.of("probe",true));var m=messages.poll(150,TimeUnit.MILLISECONDS);if(m!=null&&m.path("data").path("probe").asBoolean()){ready=true;break;}}
        assertThat(ready).isTrue();messages.clear();return new Client(client,session,messages);
    }
    void await(String reason,int seconds,BooleanSupplier predicate)throws Exception {long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!predicate.getAsBoolean()){if(System.nanoTime()>stop)throw new IllegalStateException(reason);Thread.sleep(100);}}
    @Test void realRestPrivateWsOwnershipCommitAndRollback()throws Exception {
        await("API_CLOCK_BLOCKED",25,()->{try{return rpc.block(rpc.height()).getTimestamp().longValueExact()<=java.time.Instant.now().getEpochSecond()+1;}catch(Exception e){return false;}});
        ledger.initializeEmptyFixture();String owner=signup("api_future_owner"),other=signup("api_future_other");
        long uid=jdbc.queryForObject("SELECT id FROM users WHERE login_id='api_future_owner'",Long.class),otherUid=jdbc.queryForObject("SELECT id FROM users WHERE login_id='api_future_other'",Long.class);
        // Google-only fixture must still satisfy the production login-method constraint.
        // This is an internal identity fixture, not a real Google authentication acceptance.
        jdbc.update("UPDATE users SET login_id=NULL,password_hash=NULL,google_sub=? WHERE id=?",
                "futures-api-offline-google-sub",otherUid);other=jwt.createToken(otherUid,null);
        assertThat(call(HttpMethod.GET,"/api/me",other,null,null).getBody().path("loginId").isNull()).isTrue();
        assertThat(call(HttpMethod.GET,"/api/futures/positions",null,null,null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET,"/api/admin/futures/house",owner,null,null).getStatusCode().value()).isEqualTo(403);
        try(var own=socket(uid,owner);var foreign=socket(otherUid,other)) {
            assertThat(call(HttpMethod.POST,"/api/wallet/faucet",owner,Map.of(),UUID.randomUUID().toString()).getStatusCode().is2xxSuccessful()).isTrue();
            var preview=call(HttpMethod.POST,"/api/futures/previews",owner,Map.of("action","OPEN","side","LONG","leverage",10,"marginAmount","100000"),null);
            assertThat(preview.getStatusCode().is2xxSuccessful()).isTrue();String previewId=preview.getBody().path("previewId").asText();String key=UUID.randomUUID().toString();
            assertThat(call(HttpMethod.POST,"/api/futures/positions",other,Map.of("previewId",previewId),key).getStatusCode().value()).isEqualTo(404);
            var opened=call(HttpMethod.POST,"/api/futures/positions",owner,Map.of("previewId",previewId),key);assertThat(opened.getStatusCode().value()).isEqualTo(202);
            String pid=opened.getBody().path("positionId").asText(),opid=opened.getBody().path("id").asText();
            await("API_OPEN_TIMEOUT",25,()->ledger.position(pid,uid).status().equals("OPEN"));
            assertThat(call(HttpMethod.GET,"/api/futures/positions/"+pid,other,null,null).getStatusCode().value()).isEqualTo(404);
            assertThat(call(HttpMethod.GET,"/api/futures/operations/"+opid,other,null,null).getStatusCode().value()).isEqualTo(404);
            assertThat(call(HttpMethod.GET,"/api/futures/operations/"+opid,owner,null,null).getBody().path("settlementConfirmed").asBoolean()).isTrue();
            await("PRIVATE_FUTURES_EVENT_TIMEOUT",5,()->own.messages().stream().anyMatch(m->m.path("data").path("positionId").asText().equals(pid)));
            assertThat(foreign.messages()).isEmpty();
            long originalVersion=ledger.position(pid,uid).version();String topupKey=UUID.randomUUID().toString();
            var extra=Map.of("amount","100000","expectedVersion",originalVersion);
            var callers=Executors.newFixedThreadPool(2);
            try {
                var first=callers.submit(()->call(HttpMethod.POST,"/api/futures/positions/"+pid+"/margin",owner,extra,topupKey));
                var second=callers.submit(()->call(HttpMethod.POST,"/api/futures/positions/"+pid+"/margin",owner,extra,topupKey));
                var one=first.get(10,TimeUnit.SECONDS);var two=second.get(10,TimeUnit.SECONDS);
                assertThat(one.getStatusCode().value()).isEqualTo(202);assertThat(two.getStatusCode().value()).isEqualTo(202);assertThat(one.getBody().path("id").asText()).isEqualTo(two.getBody().path("id").asText());
            }finally{callers.shutdownNow();}
            await("API_TOPUP_TIMEOUT",25,()->ledger.position(pid,uid).collateral().equals(java.math.BigInteger.TEN.pow(23).multiply(java.math.BigInteger.TWO)));
            String staleKey=UUID.randomUUID().toString();
            for(int i=0;i<2;i++) {
                var rejected=call(HttpMethod.POST,"/api/futures/positions/"+pid+"/margin",owner,extra,staleKey);
                assertThat(rejected.getStatusCode().value()).isEqualTo(409);
                assertThat(rejected.getBody().path("code").asText()).isEqualTo("FUTURES_REQUEST_REJECTED");
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE position_id=? AND action='TOP_UP'",Long.class,pid)).isEqualTo(1);
            var p=ledger.position(pid,uid);var closing=call(HttpMethod.POST,"/api/futures/previews",owner,Map.of("action","CLOSE","positionId",pid,"expectedVersion",p.version()),null);
            assertThat(closing.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(call(HttpMethod.POST,"/api/futures/positions/"+pid+"/close",owner,Map.of("previewId",closing.getBody().path("previewId").asText(),"expectedVersion",p.version()),UUID.randomUUID().toString()).getStatusCode().value()).isEqualTo(202);
            await("API_CLOSE_TIMEOUT",25,()->ledger.position(pid,uid).status().equals("CLOSED"));
            own.messages().clear();foreign.messages().clear();
            // Publish inside a real rollback transaction: no corresponding broker invalidation may escape.
            rollbackProbe(uid,pid);
            Thread.sleep(500);assertThat(own.messages().stream().anyMatch(m->m.path("data").path("operationId").asText().equals("rollback-probe"))).isFalse();assertThat(foreign.messages()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW'",BigDecimal.class,uid)).isZero();
        }
    }
    @Autowired org.springframework.context.ApplicationEventPublisher applicationEvents;
    void rollbackProbe(long uid,String pid){new TransactionTemplate(manager).executeWithoutResult(s->{applicationEvents.publishEvent(new FuturesLedger.Changed(uid,pid,"rollback-probe"));s.setRollbackOnly();});}
}
