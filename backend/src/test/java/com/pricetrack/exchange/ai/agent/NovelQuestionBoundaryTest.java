package com.pricetrack.exchange.ai.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Source-path evidence with controlled dependencies, not JWT/HTTP/provider acceptance. */
class NovelQuestionBoundaryTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final AuthenticatedUser user=new AuthenticatedUser(1L,null,UserRole.USER),admin=new AuthenticatedUser(2L,null,UserRole.ADMIN);
    JsonNode question(String id)throws Exception {
        for(var c:json.readTree(Path.of("src/test/resources/ai/novel-30-v2.json").toFile()).path("cases"))
            if(c.path("id").asText().equals(id))return c;
        throw new IllegalArgumentException();
    }
    @Test void a2RequiredReadOverridesHistoricalWrongKnowledgePlan()throws Exception {
        // Historical paid failure is retained in evidence; current deterministic guard requires a Tool.
        var model=mock(AgentModelProvider.class);var retrieval=mock(AuthorizedKnowledgeRetrieval.class);var tools=mock(ToolDispatcher.class);
        when(model.plan(anyString(),any(),any())).thenReturn(new Plan(Route.KNOWLEDGE,Subject.NONE,Usage.none()));
        when(tools.invokeForAgent(any(),eq("getCurrentReferencePrice"),any(),anyString(),any())).thenReturn(ToolResult.success("getCurrentReferencePrice","CONTROLLED",json.createObjectNode().put("provider","SIMULATED")));
        when(retrieval.search(any(),anyString(),any())).thenReturn(new AuthorizedKnowledgeRetrieval.Evidence("test",List.of()));
        try(var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json)) {
            var r=agent.answer(user,json.writeValueAsBytes(Map.of("question",question("A2").path("question").asText())));
            assertThat(r.route()).isEqualTo("MIXED");assertThat(r.toolEvidence()).hasSize(1);
            assertThat(r.metrics().toolCalls()).isEqualTo(1);assertThat(r.status()).isEqualTo("PARTIAL");
        }
        verify(tools).invokeForAgent(any(),eq("getCurrentReferencePrice"),any(),anyString(),any());verifyNoInteractions(model);
    }
    @Test void d5ForeignAndMissingCannotBecomeAnExistenceOracleOrReachModel()throws Exception {
        var model=mock(AgentModelProvider.class);var retrieval=mock(AuthorizedKnowledgeRetrieval.class);var tools=mock(ToolDispatcher.class);
        when(tools.invokeForAgent(any(),eq("getFuturesPosition"),any(),anyString(),any()))
            .thenReturn(ToolResult.failure("getFuturesPosition","RESOURCE_NOT_FOUND"));
        try(var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json)) {
            for(String id:List.of("11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222")) {
                var r=agent.answer(user,json.writeValueAsBytes(Map.of("question",question("D5").path("question").asText(),"target",Map.of("positionId",id))));
                assertThat(r.httpStatus()).isEqualTo(404);assertThat(r.error()).isEqualTo("RESOURCE_NOT_FOUND");
                assertThat(r.toolEvidence()).isEmpty();assertThat(r.metrics().modelCalls()).isZero();
                assertThat(r.metrics().retrievalCalls()).isZero();
                assertThat(json.writeValueAsString(r)).doesNotContain(id);
            }
        }
        verifyNoInteractions(model,retrieval);
    }
    @Test void c4AgentDefaultDoesNotUseTheExistingWaitFilterParameter()throws Exception {
        var model=mock(AgentModelProvider.class);var retrieval=mock(AuthorizedKnowledgeRetrieval.class);var tools=mock(ToolDispatcher.class);
        when(model.plan(anyString(),any(),any())).thenReturn(new Plan(Route.MIXED,Subject.ABNORMAL,Usage.none()));
        var arguments=new AtomicReference<JsonNode>();
        when(tools.invokeForAgent(any(),eq("listAbnormalOrders"),any(),anyString(),any())).thenAnswer(c -> {
            arguments.set(json.readTree((byte[])c.getArgument(2)).path("arguments"));
            return ToolResult.success("listAbnormalOrders","CONTROLLED",json.createObjectNode().put("filter","REVIEW_REQUIRED").set("items",json.createArrayNode()));
        });
        when(retrieval.search(any(),anyString(),any())).thenReturn(new AuthorizedKnowledgeRetrieval.Evidence("test",List.of()));
        try(var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json)) {
            var r=agent.answer(admin,json.writeValueAsBytes(Map.of("question",question("C4").path("question").asText())));
            assertThat(r.httpStatus()).isEqualTo(200);assertThat(r.status()).isEqualTo("PARTIAL");
        }
        assertThat(arguments.get().path("limit").asInt()).isEqualTo(10);
        assertThat(arguments.get().path("filter").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(arguments.get().path("waitingSeconds").asInt()).isEqualTo(300);
        var registry=new ToolRegistry(json);
        assertThat(registry.validate("listAbnormalOrders",arguments.get()).filter()).isEqualTo("REVIEW_REQUIRED");
        assertThat(registry.validate("listAbnormalOrders",json.createObjectNode().put("filter","WAITING_LONG")).filter()).isEqualTo("WAITING_LONG");
        verify(model,never()).synthesize(anyString(),any(),any(),any());
    }
    @Test void c2OrderRouteKeepsUserQuestionAlongsideOrderPolicyContext()throws Exception {
        var model=mock(AgentModelProvider.class);var retrieval=mock(AuthorizedKnowledgeRetrieval.class);var tools=mock(ToolDispatcher.class);
        when(model.plan(anyString(),any(),any())).thenReturn(new Plan(Route.MIXED,Subject.ORDER,Usage.none()));
        when(tools.invokeForAgent(any(),anyString(),any(),anyString(),any())).thenAnswer(c -> ToolResult.success(c.getArgument(1),"CONTROLLED",
            c.getArgument(1).equals("getOrder")?json.createObjectNode().put("orderId",1).put("status","FILLED"):json.createObjectNode().put("linkStatus","NOT_LINKED")));
        var query=new AtomicReference<String>();
        when(retrieval.search(any(),anyString(),any())).thenAnswer(c -> {
            query.set(c.getArgument(1));return new AuthorizedKnowledgeRetrieval.Evidence("test",List.of());
        });
        try(var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json)) {
            var r=agent.answer(user,json.writeValueAsBytes(Map.of("question",question("C2").path("question").asText(),"target",Map.of("orderId",1))));
            assertThat(r.httpStatus()).isEqualTo(200);
        }
        assertThat(query.get()).contains("주문 대기","DB 정산",question("C2").path("question").asText());
        // This proves fixed query construction, NOT that the real embedding search misses a chunk.
        verify(model,never()).synthesize(anyString(),any(),any(),any());
    }
    @Test void frozenF3IncidentSelectsConnectionPolicyWithoutReadingAnyAccountBalance()throws Exception {
        var model=mock(AgentModelProvider.class);var retrieval=mock(AuthorizedKnowledgeRetrieval.class);var tools=mock(ToolDispatcher.class);
        when(retrieval.search(any(),anyString(),any())).thenReturn(new AuthorizedKnowledgeRetrieval.Evidence("test",List.of()));
        try(var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json)){
            var r=agent.answer(user,json.writeValueAsBytes(Map.of("question",question("F3").path("question").asText())));
            assertThat(r.route()).isEqualTo("KNOWLEDGE");assertThat(r.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(r.metrics().modelCalls()).isZero();assertThat(r.toolEvidence()).isEmpty();
            // Missing authorized policy remains insufficient; routing is not proof of semantic search quality.
            verify(retrieval).search(eq(user),eq(AccountSwitchQuestion.policyQuery()),any());verifyNoInteractions(model,tools);
        }
    }
    @Test void frozenD3CannotReadOwnPortfolioAsASubstituteEvenWithAWrongStatePlan()throws Exception {
        var model=mock(AgentModelProvider.class);var retrieval=mock(AuthorizedKnowledgeRetrieval.class);var tools=mock(ToolDispatcher.class);
        when(model.plan(any(),any(),any())).thenReturn(new Plan(Route.STATE,Subject.PORTFOLIO,Usage.none()));
        when(tools.invokeForAgent(any(),eq("getPortfolio"),any(),any(),any())).thenReturn(ToolResult.success("getPortfolio","TRADING_DB",json.createObjectNode().putArray("balances").addObject().put("total","123")));
        try(var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json)){
            var r=agent.answer(admin,json.writeValueAsBytes(Map.of("question",question("D3").path("question").asText())));
            assertThat(r.status()).isEqualTo("UNSUPPORTED");assertThat(r.answer()).contains("임의 사용자를 선택하는 조회는 지원하지 않습니다").doesNotContain("총액 123");
            assertThat(r.toolEvidence()).isEmpty();verifyNoInteractions(model,retrieval,tools);
        }
    }
    @Test void frozenE2FalsePremiseIsAPolicyExplanationNotAMissingPersonalTarget()throws Exception {
        var model=mock(AgentModelProvider.class);var retrieval=mock(AuthorizedKnowledgeRetrieval.class);var tools=mock(ToolDispatcher.class);
        when(model.plan(any(),any(),any())).thenReturn(new Plan(Route.CLARIFY,Subject.NONE,Usage.none()));
        when(retrieval.search(any(),any(),any())).thenReturn(new AuthorizedKnowledgeRetrieval.Evidence("test",List.of()));
        try(var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json)){
            String question=question("E2").path("question").asText();
            var r=agent.answer(user,json.writeValueAsBytes(Map.of("question",question)));
            assertThat(r.route()).isEqualTo("KNOWLEDGE");assertThat(r.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
            verify(retrieval).search(eq(user),argThat(q->q.contains(question)&&q.contains("ExchangeVault")&&q.contains("발행·소각")),any());
            verifyNoInteractions(model,tools);
        }
    }
    @Test void relationPolicyRoutingDoesNotReplaceAnActualFillCheckOrAnExplicitTarget(){
        for(String q:List.of("내 주문이 반대 주문과 실제 체결됐는지 확인해줘", "반대 주문이랑 매칭된 내 주문이 체결됐는지 알려줘",
                "내 매수 주문이 반대주문과 매칭됐어?", "주문장 구조는 아는데 내 주문이 매칭됐어?", "가격시간우선 방식은 알아요. 제 주문은 체결되었습니까?"))
            assertThat(RequiredReadRoute.select(new AgentRequest(q,null,null))).isNull();
        assertThat(RequiredReadRoute.select(new AgentRequest("반대 주문과 연결되는 구조를 설명해주세요",1L,null))).isNull();
        assertThat(RequiredReadRoute.select(new AgentRequest("다른 사용자 말고 내 포트폴리오를 보여주세요",null,null))).isNull();
        for(String q:List.of("매수할 때 상대 주문은 어디에서 연결되는 구조인가요?", "mSEC 거래의 주문장 가격시간우선 매칭 정책이 맞나요?"))
            assertThat(RequiredReadRoute.select(new AgentRequest(q,null,null)).route()).isEqualTo(Route.KNOWLEDGE);
    }
}
