package com.pricetrack.exchange.futures.chain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.agent.AgentModelProvider.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.skill.*;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.ai.tool.read.*;
import com.pricetrack.exchange.ai.tool.receipt.ReceiptReader;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.futures.audit.FuturesAuditStore;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.runtime.*;
import com.pricetrack.exchange.user.UserRole;
import com.pricetrack.exchange.wallet.WalletService;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/** Real DB/Anvil/worker evidence, offline synthetic fixture market and Mock LLM/retrieval. NOT actual AI provider evaluation. */
@EnabledIfEnvironmentVariable(named="FUTURES_AI_E2E_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FuturesAutoPostgresAnvilE2ETest.FixtureMarket.class)
class FuturesAiPostgresAnvilE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry r)throws Exception {
        var p=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("FUTURES_AI_E2E_RUN"));
        if(p==null||!p.getProperty("app.futures.runtime-enabled","").equals("true"))throw new IllegalStateException("NEW_AI_FIXTURE_REQUIRED");
        p.forEach((k,v)->r.add(k.toString(),()->v));
    }
    @Autowired FuturesLedger ledger;@Autowired FuturesService commands;@Autowired FuturesRpcGateway rpc;
    @Autowired FuturesToolReader reader;@Autowired FuturesAuditStore cuts;@Autowired AuthService auth;@Autowired WalletService wallets;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired FuturesAutoPostgresAnvilE2ETest.Scenario scenario;
    void await(String code,int seconds,BooleanSupplier predicate)throws Exception {long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!predicate.getAsBoolean()){if(System.nanoTime()>stop)throw new IllegalStateException(code);Thread.sleep(100);}}
    ToolContext ctx(long uid,UserRole role){return new ToolContext(uid,role,"test","READ_ONLY",null);}
    @Test void actualLiquidationCanonicalJournalAndOwnedAgentSkill()throws Exception {
        await("CLOCK_BLOCKED",25,()->{try{return rpc.block(rpc.height()).getTimestamp().longValueExact()<=Instant.now().getEpochSecond()+1;}catch(Exception failure){return false;}});
        ledger.initializeEmptyFixture();auth.signup(new AuthController.SignupRequest("futures_ai_owner","fixture-password","AI owner"));
        long uid=jdbc.queryForObject("SELECT id FROM users WHERE login_id='futures_ai_owner'",Long.class);wallets.faucet(uid,UUID.randomUUID().toString());
        var preview=commands.preview(uid,Action.OPEN,null,0,Side.LONG,10,BigInteger.TEN.pow(23));
        var opening=commands.consume(uid,preview.id(),UUID.randomUUID().toString(),Action.OPEN,null,0);String pid=opening.positionId();
        await("OPEN_TIMEOUT",25,()->ledger.position(pid,uid).status().equals("OPEN"));scenario.price("60000");
        await("CALL_TIMEOUT",25,()->ledger.position(pid,uid).riskStatus().equals("GRACE"));
        var call=reader.evidence(ctx(uid,UserRole.USER),pid);assertThat(call.path("verification").asText()).as(call.path("reason").asText()).isEqualTo("VERIFIED");
        await("LIQUIDATION_TIMEOUT",90,()->ledger.position(pid,uid).status().equals("LIQUIDATED"));
        var before=cuts.fingerprint(cuts.cut());var evidence=reader.evidence(ctx(uid,UserRole.USER),pid);
        assertThat(evidence.path("verification").asText()).as(evidence.path("reason").asText()).isEqualTo("VERIFIED");assertThat(evidence.path("operations")).hasSize(2);
        assertThat(reader.evidence(ctx(uid+1000,UserRole.ADMIN),pid).path("verification").asText()).isEqualTo("VERIFIED");
        assertThatThrownBy(()->reader.evidence(ctx(uid+1000,UserRole.USER),pid)).hasMessage("RESOURCE_NOT_FOUND");
        var model=mock(AgentModelProvider.class);var retrieval=mock(AuthorizedKnowledgeRetrieval.class);
        var doc=new KnowledgeHit("futures-policy","onchain-settlement-policy.md","선물","위험","3","USER","DB 담보와 60초 유예 및 새 가격 재검증",.8);
        when(retrieval.searchScoped(any(),anyString(),any(),any())).thenReturn(new AuthorizedKnowledgeRetrieval.Evidence("fixture-index",List.of(doc)));
        when(model.synthesize(anyString(),any(),any(),any())).thenAnswer(c->{JsonNode input=c.getArgument(2);assertThat(input.toString()).doesNotContain(pid,"rawTransaction","signature","nonce","accountRef");
            return new Generated("ANSWERED","선택한 청산의 정규 체인 근거와 DB journal을 조회했습니다.",List.of("futures-policy","tool-2"),List.of(new FactReference("tool-2","/verification","VERIFIED")),List.of(),List.of(),Usage.none());});
        try(var dispatcher=new ToolDispatcher(new ToolProperties(true),new ToolRegistry(json),mock(ToolReadFacade.class),mock(ReceiptReader.class),new ToolAudit(),json).futures(()->reader);
            var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,dispatcher,json,Duration.ofSeconds(40),new SkillProperties(true),new SkillRegistry(json))) {
            byte[] request=("{\"question\":\"내 Long 포지션은 왜 청산됐나요\",\"target\":{\"positionId\":\""+pid+"\"},\"skillId\":\"futures-liquidation-diagnosis\"}").getBytes(StandardCharsets.UTF_8);
            var answer=agent.answer(new AuthenticatedUser(uid,null,UserRole.USER),request);
            assertThat(answer.status()).as(answer.uncertainties().toString()).isEqualTo("ANSWERED");assertThat(answer.skill().diagnosis().classification()).isEqualTo("FUTURES_LIQUIDATION_VERIFIED");assertThat(answer.metrics().toolCalls()).isEqualTo(2);
            var denied=agent.answer(new AuthenticatedUser(uid+1000,null,UserRole.USER),request);assertThat(denied.httpStatus()).isEqualTo(404);
            assertThat(cuts.fingerprint(cuts.cut())).isEqualTo(before);
            var folder=java.nio.file.Path.of(System.getenv("FUTURES_AI_E2E_RUN")).toRealPath();
            java.nio.file.Files.writeString(folder.resolve("ai.evidence.json"),json.writeValueAsString(Map.of("readOnlyLedgerUnchanged",true,"llm","MOCK_NOT_ACTUAL_PROVIDER","retrieval","FIXTURE_NOT_PAID_INDEX","call",call,"liquidation",evidence,"answer",answer)),java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }
}
