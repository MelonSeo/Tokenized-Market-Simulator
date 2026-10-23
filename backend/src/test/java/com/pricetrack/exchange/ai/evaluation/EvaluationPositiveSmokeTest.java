package com.pricetrack.exchange.ai.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.skill.*;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Real Dispatcher/Agent/Skill handlers, shared live-harness fixtures. No provider/DB/RPC. */
class EvaluationPositiveSmokeTest {
    final ObjectMapper json=EvaluationJson.mapper();
    Map<String,Object> arguments(String name,EvaluationToolFixture f) {
        return switch(name){
            case "getOrder","getBlockchainTransaction","getReceiptSummary"->Map.of("orderId",1);
            case "getQuote"->Map.of("quoteId",f.quote);
            case "getFuturesPosition","getFuturesLiquidationEvidence"->Map.of("positionId",f.pid);
            default->Map.of();
        };
    }
    @ParameterizedTest @ValueSource(strings={"getOrder","getQuote","getBlockchainTransaction","getReceiptSummary","getMarketStatus","getCurrentReferencePrice","getPortfolio","listAbnormalOrders","getFuturesPosition","getFuturesLiquidationEvidence"})
    void everyActualToolHasSerializablePositiveResponse(String name)throws Exception {
        var f=EvaluationToolFixture.offline(json);
        try(var tools=f.dispatcher()) {
            var r=tools.invoke(name.equals("listAbnormalOrders")?f.admin:f.user,name,json.writeValueAsBytes(Map.of("arguments",arguments(name,f))));
            assertThat(r.status()).as(name).isEqualTo("SUCCESS");assertThat(r.data()).isNotNull();
            assertThat(json.readTree(json.writeValueAsBytes(r)).path("retrievedAt").isTextual()).isTrue();
        }
    }
    AgentModelProvider model() {
        var model=mock(AgentModelProvider.class);
        when(model.synthesize(any(),any(),any(),any())).thenAnswer(c->{
            JsonNode input=c.getArgument(2);var first=input.path("tools").get(0);var data=first.path("data");
            String pointer=data.has("position")?"/position/status":data.has("status")?"/status":data.has("storedStatus")?"/storedStatus":"/marketStatus";
            String id=first.path("evidenceId").asText();
            return new Generated("ANSWERED","오프라인 모형은 제공된 사실과 정책만 설명합니다.",List.of("policy",id),
                List.of(new FactReference(id,pointer,data.at(pointer).asText())),List.of(),List.of(),Usage.none());
        });return model;
    }
    AuthorizedKnowledgeRetrieval retrieval() {
        var r=mock(AuthorizedKnowledgeRetrieval.class);
        var e=new AuthorizedKnowledgeRetrieval.Evidence("offline",List.of(new KnowledgeHit("policy","onchain-settlement-policy.md","정책","검증","1","USER","DB 담보, 5퍼센트 유지증거금, 60초 유예",.8)));
        when(r.searchScoped(any(),any(),any(),any())).thenReturn(e);when(r.search(any(),any(),any())).thenReturn(e);return r;
    }
    byte[] request(String skill,EvaluationToolFixture f)throws Exception {
        var b=new LinkedHashMap<String,Object>();b.put("question","조회된 상태와 정책을 진단해 주세요");b.put("skillId",skill);
        if(skill.equals("settlement-debugging"))b.put("target",Map.of("orderId",1));
        if(skill.equals("signed-quote-diagnosis"))b.put("target",Map.of("quoteId",f.quote));
        if(skill.equals("futures-liquidation-diagnosis"))b.put("target",Map.of("positionId",f.pid));
        return json.writeValueAsBytes(b);
    }
    @ParameterizedTest @ValueSource(strings={"settlement-debugging","signed-quote-diagnosis","market-availability-diagnosis","futures-liquidation-diagnosis"})
    void allFourSkillsTraverseRealDispatcherAndHandlers(String skill)throws Exception {
        var f=EvaluationToolFixture.offline(json);var model=model();var retrieval=retrieval();
        try(var tools=f.dispatcher();var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json,Duration.ofSeconds(40),new SkillProperties(true),new SkillRegistry(json))) {
            var r=agent.answer(skill.equals("settlement-debugging")?f.admin:f.user,request(skill,f));
            assertThat(r.httpStatus()).isEqualTo(200);assertThat(r.status()).isIn("ANSWERED","PARTIAL");
            assertThat(r.skill().id()).isEqualTo(skill);assertThat(r.skill().diagnosis().classification()).isNotEqualTo("INCONSISTENCY_OBSERVED");
            assertThat(r.toolEvidence()).isNotEmpty().allSatisfy(e->assertThat(e.status()).isEqualTo("SUCCESS"));
            assertThat(r.uncertainties()).doesNotContain("SYNTHESIS_UNAVAILABLE","RAG_UNAVAILABLE");
            assertThat(r.skill().trace()).anyMatch(t->t.stepId().equals("SUMMARIZE")&&t.resultCode().equals("VALIDATED"));
        }
    }
    @Test void syntheticMarketFixtureMatchesProductStatusAndSkillUsesObservedProvider()throws Exception {
        var f=EvaluationToolFixture.offline(json);var model=model();var retrieval=retrieval();
        try(var tools=f.dispatcher();var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json,Duration.ofSeconds(40),new SkillProperties(true),new SkillRegistry(json))) {
            var r=agent.answer(f.user,request("market-availability-diagnosis",f));
            assertThat(r.httpStatus()).isEqualTo(200);
            assertThat(r.toolEvidence()).singleElement().satisfies(e->{
                assertThat(e.tool()).isEqualTo("getCurrentReferencePrice");
                assertThat(e.status()).isEqualTo("SUCCESS");
                assertThat(e.data().path("provider").asText()).isEqualTo("SIMULATED");
                assertThat(e.data().path("priceStatus").asText()).isEqualTo("SIMULATED");
                assertThat(e.data().path("marketStatus").asText()).isEqualTo("OPEN");
            });
            assertThat(r.skill().diagnosis().observedFindings()).extracting(SkillResult.Finding::code)
                .contains("SIMULATED_REFERENCE_OBSERVED").doesNotContain("PRICE_UNAVAILABLE_OBSERVED","MARKET_CLOSED_OBSERVED");
            verify(model,never()).planWithSkills(any(),any(),any(),any());
        }
    }
    @Test void uncertainFuturesIsPartialWithoutModelSynthesis()throws Exception {
        var f=EvaluationToolFixture.offline(json);f.unverified.set(true);var model=model();var retrieval=retrieval();
        try(var tools=f.dispatcher();var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json,Duration.ofSeconds(40),new SkillProperties(true),new SkillRegistry(json))) {
            var r=agent.answer(f.user,request("futures-liquidation-diagnosis",f));
            assertThat(r.status()).isEqualTo("PARTIAL");assertThat(r.skill().diagnosis().classification()).isEqualTo("FUTURES_EVIDENCE_UNVERIFIED");
            verify(model,never()).synthesize(any(),any(),any(),any());
            assertThat(EvaluationVerdict.classify(r,true,true)).isEqualTo(new EvaluationVerdict.Result("COMPLETED","NOT_RUN","PASS"));
        }
    }
    @Test void negativePermissionsAndMalformedArgumentsStillBlock()throws Exception {
        var f=EvaluationToolFixture.offline(json);var foreign=new AuthenticatedUser(910003L,null,UserRole.USER);
        try(var tools=f.dispatcher()) {
            assertThat(tools.invoke(f.user,"listAbnormalOrders",json.writeValueAsBytes(Map.of("arguments",Map.of()))).error()).isEqualTo("TOOL_FORBIDDEN");
            assertThat(tools.invoke(foreign,"getOrder",json.writeValueAsBytes(Map.of("arguments",Map.of("orderId",1)))).error()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(tools.invoke(f.user,"getOrder","{}".getBytes()).error()).isEqualTo("INVALID_TOOL_ARGUMENTS");
        }
    }
    @Test void mapperWireOutputMatchesProductsBootAutoconfigurationWithoutStartingServer() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class)).run(ctx->{
            var boot=ctx.getBean(ObjectMapper.class);
            var value=new ToolResult("getOrder","1","SUCCESS",Instant.parse("2026-10-20T00:00:00Z"),"TRADING_DB",json.createObjectNode().put("amount","0.000000000000000001"),null);
            assertThat(json.readTree(json.writeValueAsBytes(value))).isEqualTo(boot.readTree(boot.writeValueAsBytes(value)));
        });
    }
    @Test void fixedSixteenQuestionsCompleteOfflineWithHonestVerdicts()throws Exception {
        var f=EvaluationToolFixture.offline(json);var model=model();var retrieval=retrieval();
        when(model.planWithSkills(any(),any(),any(),any())).thenAnswer(c->{
            String q=c.getArgument(0);Subject target=c.getArgument(1);
            if(target!=Subject.NONE)return new Plan(q.contains("상태만")?Route.STATE:Route.MIXED,target,Usage.none());
            return new Plan(q.contains("quoteId는")?Route.KNOWLEDGE:Route.STATE,
                q.contains("quoteId는")?Subject.NONE:q.contains("포트폴리오")?Subject.PORTFOLIO:q.contains("이상 주문")?Subject.ABNORMAL:Subject.PRICE,Usage.none());
        });
        // Knowledge-only synthesis must not invent Tool evidence.
        doReturn(new Generated("ANSWERED","승인 문서에 근거한 모형 답변",List.of("policy"),List.of(),List.of(),List.of(),Usage.none()))
            .when(model).synthesize(any(),eq(Route.KNOWLEDGE),any(),any());
        var results=new ArrayList<AgentResponse>();
        try(var tools=f.dispatcher();var agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json,Duration.ofSeconds(40),new SkillProperties(true),new SkillRegistry(json))) {
            var qs=ApprovedAiModelEvaluationTest.fixedQuestions();assertThat(qs).hasSize(16);
            for(int i=0;i<qs.size();i++) {
                var body=new LinkedHashMap<String,Object>();body.put("question",qs.get(i));
                if(Set.of(3,5,6,8).contains(i))body.put("target",Map.of("orderId",1));
                if(Set.of(4,9).contains(i))body.put("target",Map.of("quoteId",f.quote));
                if(i>=11)body.put("target",Map.of("positionId",f.pid));
                String skill=switch(i){case 8->"settlement-debugging";case 9->"signed-quote-diagnosis";case 10->"market-availability-diagnosis";case 11,15->"futures-liquidation-diagnosis";default->null;};
                if(skill!=null)body.put("skillId",skill);if(i==15)f.unverified.set(true);
                var result=agent.answer(i==7||i==8?f.admin:f.user,json.writeValueAsBytes(body));results.add(result);
                assertThat(result.httpStatus()).as("Q%d",i+1).isEqualTo(200);
                assertThat(result.status()).as("Q%d",i+1).isIn("ANSWERED","PARTIAL");
                assertThat(result.uncertainties()).as("Q%d",i+1).doesNotContain("SYNTHESIS_UNAVAILABLE","RAG_UNAVAILABLE");
                assertThat(EvaluationVerdict.classify(result,true,false).correctness()).isEqualTo("REVIEW_REQUIRED");
            }
        }
        assertThat(results.getLast().status()).isEqualTo("PARTIAL");
        assertThat(results.getLast().metrics().modelCalls()).isZero();
        assertThat(results.subList(1,16).stream().mapToInt(r->r.metrics().modelCalls()).sum()).isLessThanOrEqualTo(24);
    }
}
