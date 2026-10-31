package com.pricetrack.exchange.ai.agent;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.AiProperties;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;

class OpenAiAgentProviderTest {
    final ObjectMapper json=new ObjectMapper();
    HttpServer server; String body,requested;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",e -> {
            requested=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);
            try(var out=e.getResponseBody()){out.write(bytes);}
        });server.start();
    }
    @AfterEach void close(){server.stop(0);}
    OpenAiAgentProvider provider(){
        var p=new AiProperties(true,"jdbc:postgresql://127.0.0.1:1/test","test","test",".",".","fake-test-key",
            "http://127.0.0.1:"+server.getAddress().getPort()+"/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        return new OpenAiAgentProvider(new OpenAiProvider(p,json).budget(com.pricetrack.exchange.ai.UnpaidLoopbackBudget.forBase(p.apiBase())),json);
    }
    void output(Object data) throws Exception {
        body=json.writeValueAsString(Map.of("status","completed","usage",Map.of("input_tokens",21,"output_tokens",9),
            "output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",json.writeValueAsString(data)))))));
    }
    @Test void closedPlanDisablesStorageAndArbitraryToolsAndRecordsActualUsage() throws Exception {
        output(Map.of("route","MIXED","subject","ORDER"));
        var result=provider().plan("왜 대기?",Subject.ORDER,Duration.ofSeconds(5));
        assertThat(result.route()).isEqualTo(Route.MIXED);assertThat(result.usage().inputTokens()).isEqualTo(21);
        var request=json.readTree(requested);
        assertThat(request.path("store").asBoolean(true)).isFalse();assertThat(request.has("tools")).isFalse();
        assertThat(request.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(request.path("text").path("format").path("schema").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(request.path("max_output_tokens").asInt()).isEqualTo(400);
        assertThat(request.path("instructions").asText()).contains("실행 조건·위험·실패 이유", "무엇을 실행할지 대신 결정", "정책 설명으로 분류한다");
    }
    @Test void sixFieldSynthesisParsesEvidenceAndRejectsUnexpectedFields() throws Exception {
        Map<String,Object> generated=new LinkedHashMap<>(Map.of("status","ANSWERED","interpretation","출처 근거 설명",
            "citationIds",List.of("knowledge-1","tool-1"),"facts",List.of(Map.of("evidenceId","tool-1","pointer","/status","value","PENDING_ONCHAIN")),
            "uncertainties",List.of(),"recommendedNextCheck",List.of()));
        output(generated);
        var result=provider().synthesize("왜?",Route.MIXED,json.createObjectNode(),Duration.ofSeconds(10));
        assertThat(result.status()).isEqualTo("ANSWERED");assertThat(result.facts()).hasSize(1);
        assertThat(result.usage().outputTokens()).isEqualTo(9);
        assertThat(json.readTree(requested).path("max_output_tokens").asInt()).isEqualTo(1600);
        assertThat(requested).contains("/data 접두사를 붙이지 않는다", "/data/status");
        generated.put("role","ADMIN");output(generated);
        assertThatThrownBy(() -> provider().synthesize("왜?",Route.MIXED,json.createObjectNode(),Duration.ofSeconds(10)))
            .hasMessage("INVALID_AGENT_EVIDENCE");
    }
    @Test void unknownPlanEnumAndZeroBudgetFailClosed() throws Exception {
        output(Map.of("route","EXECUTE_SQL","subject","ORDER"));
        assertThatThrownBy(() -> provider().plan("정책",Subject.NONE,Duration.ofSeconds(5))).hasMessage("INVALID_AGENT_PLAN");
        assertThatThrownBy(() -> provider().plan("정책",Subject.NONE,Duration.ZERO)).isInstanceOf(RuntimeException.class);
    }
    @Test void eligibleSkillIsClosedAndForgedSelectionIsRejected()throws Exception {
        output(Map.of("route","MIXED","subject","QUOTE","skillId","signed-quote-diagnosis"));
        assertThat(provider().planWithSkills("견적 진단",Subject.QUOTE,List.of("signed-quote-diagnosis"),Duration.ofSeconds(5)).skillId()).isEqualTo("signed-quote-diagnosis");
        var schema=json.readTree(requested).path("text").path("format").path("schema");
        assertThat(schema.path("properties").path("skillId").path("enum")).isEqualTo(json.valueToTree(List.of("signed-quote-diagnosis","NONE")));
        output(Map.of("route","MIXED","subject","QUOTE","skillId","settlement-debugging"));
        assertThatThrownBy(() -> provider().planWithSkills("진단",Subject.QUOTE,List.of("signed-quote-diagnosis"),Duration.ofSeconds(5))).hasMessage("INVALID_AGENT_PLAN");
    }
    @Test void productionSynthesisKeepsMeasuredSuffixAndScopesUncertaintiesWithoutChangingSchema()throws Exception {
        output(Map.of("status","ANSWERED","interpretation","관측 범위 설명","citationIds",List.of(),
            "facts",List.of(),"uncertainties",List.of(),"recommendedNextCheck",List.of()));
        provider().synthesize("관측 설명",Route.KNOWLEDGE,json.createObjectNode(),Duration.ofSeconds(10));
        var payload=json.readTree(requested);String instructions=payload.path("instructions").asText();
        String selected=json.readTree(new org.springframework.core.io.ClassPathResource("ai/latest-quality-prompts.json")
            .getContentAsString(StandardCharsets.UTF_8)).path("experiment2").asText();
        assertThat(instructions).contains("\n"+selected+"\n질문에 충분한 범위:");
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        assertThat(HexFormat.of().formatHex(digest.digest(selected.getBytes(StandardCharsets.UTF_8))))
            .isEqualTo("710f5eb3098efe88927200d3404f30d7cb25cecb91ceb2d89af879425e84a5bb");
        String base=instructions.substring(0,instructions.indexOf("\n"+selected));
        assertThat(base).contains("서버는 uncertainties가 하나라도 있으면 PARTIAL로 표시한다", "receipt가 없으면 핵심 누락", "INCONCLUSIVE이면 핵심 누락");
        assertThat(base).doesNotContain("과거 원인·실시간 공급자 상태는 uncertainties에 남긴다", "실제 숫자는 facts를 가리킨다");
        assertThat(base).contains("정책으로 설명할 수 있는 부분", "서버가 PARTIAL로 표시", "핵심에 답할 근거가 전혀 없을 때만");
        assertThat(HexFormat.of().formatHex(digest.digest(base.getBytes(StandardCharsets.UTF_8))))
            .isEqualTo("e0253fc871f8512b3d3d6710e44879ba33140bd9d5a6cf2373c79edd63ec5d75");
        assertThat(base).contains("amountRelation.inputIncludesFee=true","quotedTotalDebit","includedFee","hasMore/scope");
        assertThat(payload.path("max_output_tokens").asInt()).isEqualTo(1600);
        assertThat(payload.at("/text/format/schema/properties/facts/maxItems").asInt()).isEqualTo(20);
        assertThat(payload.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(payload.path("store").asBoolean(true)).isFalse();
    }
    @Test void missingQualityResourceFailsAiOperationBeforeAnyProviderRequest() {
        var original=Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(new ClassLoader(original){
                @Override public java.net.URL getResource(String name){
                    return name.equals("ai/agent-quality-instructions.txt")?null:super.getResource(name);
                }
            });
            assertThatThrownBy(()->provider().synthesize("설명",Route.KNOWLEDGE,json.createObjectNode(),Duration.ofSeconds(10)))
                .hasMessage("AI_QUALITY_PROMPT_UNAVAILABLE");
            assertThat(requested).isNull();
        } finally {Thread.currentThread().setContextClassLoader(original);}
    }
}
