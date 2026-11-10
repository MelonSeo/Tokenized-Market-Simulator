package com.pricetrack.exchange.ai.agent;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StateExplanationTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void portfolioAlwaysNamesOwnScopeWithoutInventingMissingBalances(){
        var empty=json.createObjectNode();empty.putArray("balances");
        assertThat(StateExplanation.explain(List.of(item("getPortfolio",empty)))).contains("로그인한 본인의 모의장부", "타인 잔고가 아닙니다").doesNotContain("총액 0", "가용 0");
    }
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
    @Test void abnormalWaitingCriterionIsOnlyClaimedWhenActuallyApplied(){
        var n=json.createObjectNode().put("filter","REVIEW_REQUIRED").put("waitingSeconds",300).put("waitingSecondsApplied",false);
        assertThat(StateExplanation.explain(List.of(item("listAbnormalOrders",n))))
            .contains("대기시간 기준은 적용하지 않음").doesNotContain("대기 기준 300초");
        n.put("filter","WAITING_LONG").put("waitingSecondsApplied",true);
        assertThat(StateExplanation.explain(List.of(item("listAbnormalOrders",n)))).contains("대기 기준 300초");
        n.remove("waitingSecondsApplied");
        assertThat(StateExplanation.explain(List.of(item("listAbnormalOrders",n))))
            .contains("적용 여부 미확인").doesNotContain("대기 기준 300초");
        n.put("waitingSecondsApplied","true");
        assertThat(StateExplanation.explain(List.of(item("listAbnormalOrders",n)))).contains("적용 여부 미확인");
    }
    @Test void receiptFallbackPreservesObservedDimensionsWithoutInventingExecution(){
        var n=json.createObjectNode().put("receiptStatus","FOUND").put("executionStatus","SUCCESS").put("eventValidation","MATCH")
            .put("confirmations",12).put("requiredConfirmations",2).put("databaseStatus","CONFIRMED").put("txHash","SECRET_HASH");
        assertThat(StateExplanation.explain(List.of(item("getReceiptSummary",n)))).contains("FOUND","SUCCESS","MATCH","12/필요 2","CONFIRMED","새 거래 실행이나 전체 체인 감사를 뜻하지 않습니다")
            .doesNotContain("SECRET_HASH");
        n.put("receiptStatus","NOT_FOUND").remove("executionStatus");
        assertThat(StateExplanation.explain(List.of(item("getReceiptSummary",n)))).contains("NOT_FOUND","실행 상태 미확인").doesNotContain("실행 상태 SUCCESS");
    }
    @Test void currentRecoveryDoesNotBecomeConfirmedClearCallAndPrivatePendingIdIsHidden(){
        var n=json.createObjectNode();n.putObject("position").put("status","OPEN").put("riskStatus","GRACE").put("priceAvailable",true)
            .put("equity","120.000000000000000000").put("maintenance","100.000000000000000000").put("pendingOperation","PRIVATE_OPERATION");
        var e=json.createObjectNode().put("verification","INCONCLUSIVE").put("scope","SELECTED_CALL_AND_LIQUIDATION_ONLY");
        assertThat(StateExplanation.explain(List.of(item("getFuturesPosition",n),item("getFuturesLiquidationEvidence",e))))
            .contains("DB 기록 상태는 OPEN","위험 상태는 GRACE","120.000000000000000000","100.000000000000000000","처리 중 작업은 있음",
                "INCONCLUSIVE","CLEAR_CALL 전체 이력을 검증한 것이 아닙니다","경고 해제 확정이나 체인 CLEAR_CALL 성공을 추론하지 않습니다").doesNotContain("PRIVATE_OPERATION");
    }
    @Test void cAccountAndPortfolioAreDistinctSourcesNotAnAggregatedBalance(){
        var n=json.createObjectNode().put("walletBalance","997000").put("free","3500").put("locked","0");
        assertThat(StateExplanation.explain(List.of(item("getCAccount",n)))).contains("getPortfolio는 PostgreSQL 모의장부","별도 getCAccount 조회","두 잔고를 합산하지 않습니다","3500");
    }
}
