package com.pricetrack.exchange.ai.agent;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StateExplanationTest {
    final ObjectMapper json=new ObjectMapper();
    AgentResponse.ToolEvidence item(String tool,JsonNode data){return new AgentResponse.ToolEvidence("t",tool,"1","SUCCESS",null,"TRADING_DB",data,null);}
    @Test void reportsExactDecimalsNotIdentityOrAdditionalInference(){
        var n=json.createObjectNode();n.put("loginId","SECRET");n.putArray("balances").addObject().put("symbol","mKRW")
            .put("total","1000.000000000000000001").put("locked","250.000000000000000000").put("available","750.000000000000000001");
        assertThat(StateExplanation.explain(List.of(item("getPortfolio",n)))).contains("1000.000000000000000001","250.000000000000000000","750.000000000000000001").doesNotContain("SECRET");
    }
    @Test void missingAndAdversarialFieldsAreNotExposed(){
        var n=json.createObjectNode().put("provider","<script>secret</script>").put("marketStatus","OPEN");
        assertThat(StateExplanation.explain(List.of(item("getMarketStatus",n)))).contains("OPEN","미확인").doesNotContain("script","secret");
        assertThat(StateExplanation.explain(List.of(item("getOrder",json.createObjectNode().put("status","FILLED"))))).contains("FILLED","별개");
    }
    @Test void pageCountNotGlobalCountAndFailedToolIgnored(){
        var n=json.createObjectNode();n.putArray("items").addObject();
        assertThat(StateExplanation.explain(List.of(item("listAbnormalOrders",n)))).contains("1건","전체 이력 건수가 아닙니다");
        var failed=new AgentResponse.ToolEvidence("t","getOrder","1","ERROR",null,"TRADING_DB",json.createObjectNode().put("status","SECRET"),"TOOL_TIMEOUT");
        assertThat(StateExplanation.explain(List.of(failed))).doesNotContain("SECRET");
    }
    @Test void abnormalPageNamesOnlyTheObservedFilterWithoutGuessingOrExposingCursor(){
        for(String filter:List.of("REVIEW_REQUIRED","WAITING_LONG")){
            var n=json.createObjectNode().put("filter",filter).put("nextCursor","PRIVATE_CURSOR");
            n.putArray("items").addObject();
            assertThat(StateExplanation.explain(List.of(item("listAbnormalOrders",n))))
                .contains("필터는 "+filter,"1건","전체 이력 건수가 아닙니다")
                .doesNotContain("PRIVATE_CURSOR");
        }
        var missing=json.createObjectNode();missing.putArray("items");
        assertThat(StateExplanation.explain(List.of(item("listAbnormalOrders",missing))))
            .contains("필터는 미확인").doesNotContain("REVIEW_REQUIRED","WAITING_LONG");
        missing.put("filter","<script>PRIVATE_FILTER</script>");
        assertThat(StateExplanation.explain(List.of(item("listAbnormalOrders",missing))))
            .contains("필터는 미확인").doesNotContain("script","PRIVATE_FILTER");
    }
}
