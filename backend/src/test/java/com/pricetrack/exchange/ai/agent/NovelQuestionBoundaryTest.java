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
    @Test void a2CapturedKnowledgePlanDoesNotReadTheCurrentProvider()throws Exception {
        // Reproduce the observed routing consequence, not the unpaid model's classification quality.
        var model=mock(AgentModelProvider.class);var retrieval=mock(AuthorizedKnowledgeRetrieval.class);var tools=mock(ToolDispatcher.class);
        when(model.plan(anyString(),any(),any())).thenReturn(new Plan(Route.KNOWLEDGE,Subject.NONE,Usage.none()));
        when(retrieval.search(any(),anyString(),any())).thenReturn(new AuthorizedKnowledgeRetrieval.Evidence("test",List.of()));
        try(var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json)) {
            var r=agent.answer(user,json.writeValueAsBytes(Map.of("question",question("A2").path("question").asText())));
            assertThat(r.route()).isEqualTo("KNOWLEDGE");assertThat(r.toolEvidence()).isEmpty();
            assertThat(r.metrics().toolCalls()).isZero();assertThat(r.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        }
        verifyNoInteractions(tools);verify(model,never()).synthesize(anyString(),any(),any(),any());
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
        assertThat(arguments.get().has("filter")).isFalse();
        var registry=new ToolRegistry(json);
        assertThat(registry.validate("listAbnormalOrders",arguments.get()).filter()).isEqualTo("REVIEW_REQUIRED");
        assertThat(registry.validate("listAbnormalOrders",json.createObjectNode().put("filter","WAITING_LONG")).filter()).isEqualTo("WAITING_LONG");
        verify(model,never()).synthesize(anyString(),any(),any(),any());
    }
    @Test void c2OrderRouteSearchDoesNotCarryTheUsersWebsocketQuestion()throws Exception {
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
        assertThat(query.get()).contains("주문 대기","DB 정산").doesNotContain("알림","WebSocket","소켓","늦게");
        // This proves fixed query construction, NOT that the real embedding search misses a chunk.
        verify(model,never()).synthesize(anyString(),any(),any(),any());
    }
}
