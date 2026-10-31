package com.pricetrack.exchange.ai.readiness;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiReadinessAssertionsTest {
    AiReadinessHttpPerformanceTest harness(){var h=new AiReadinessHttpPerformanceTest();h.json=new ObjectMapper();h.ownerToken="owner-canary";h.otherToken="other-canary";h.adminToken="admin-canary";return h;}
    com.fasterxml.jackson.databind.node.ObjectNode state(AiReadinessHttpPerformanceTest h){
        var b=h.json.createObjectNode().put("httpStatus",200).put("route","STATE").put("status","ANSWERED").putNull("error");
        b.putArray("uncertainties");b.putObject("metrics").put("modelCalls",0);
        b.putArray("toolEvidence").addObject().put("tool","getPortfolio").putObject("data").put("available","123.25");return b;
    }
    @Test void normalStateHasNoModelCall(){var h=harness();h.assertNormal("STATE",new AiReadinessHttpPerformanceTest.Reply(200,state(h),1));}
    @Test void partial200CannotBecomeSuccessfulLoad(){var h=harness();var b=state(h).put("status","PARTIAL");assertThatThrownBy(()->h.assertNormal("STATE",new AiReadinessHttpPerformanceTest.Reply(200,b,1))).isInstanceOf(AssertionError.class);}
    @Test void degraded200CannotBecomeSuccessfulLoad(){var h=harness();var b=state(h);b.withArray("uncertainties").add("RAG_UNAVAILABLE");assertThatThrownBy(()->h.assertNormal("STATE",new AiReadinessHttpPerformanceTest.Reply(200,b,1))).isInstanceOf(AssertionError.class);}
    @Test void missingKnowledgeCannotBecomeSuccessfulLoad(){var h=harness();var b=state(h).put("route","KNOWLEDGE");b.putArray("knowledgeSources");assertThatThrownBy(()->h.assertNormal("KNOWLEDGE",new AiReadinessHttpPerformanceTest.Reply(200,b,1))).isInstanceOf(AssertionError.class);}
    @Test void nestedPrivateFieldsDetected(){var h=harness();var n=h.json.createObjectNode();n.putArray("rows").addObject().put("userId",1).putObject("nested").put("signature","canary");h.inspectModelData(n);assertThat(h.privacyViolations.get()).isEqualTo(2);}
    @Test void publicFailureDiagnosticsContainOnlyFixedCodes(){var h=harness();var b=state(h).put("status","PARTIAL");b.withArray("uncertainties").add("sk-private-canary");assertThatThrownBy(()->h.assertNormal("STATE",new AiReadinessHttpPerformanceTest.Reply(200,b,1))).isInstanceOf(AssertionError.class);assertThat(h.result.toString()).contains("PARTIAL","UNAVAILABLE").doesNotContain("sk-private-canary");}
    @Test void realBrowserReadinessUsesProductOneSecondTicksNotQuietUnitTestDefaults(){var annotation=AiReadinessHttpPerformanceTest.class.getAnnotation(org.springframework.boot.test.context.SpringBootTest.class);assertThat(annotation.properties()).contains("app.price.provider=simulated","app.price.update-interval-ms=1000","app.price.initial-delay-ms=1000");}
}
