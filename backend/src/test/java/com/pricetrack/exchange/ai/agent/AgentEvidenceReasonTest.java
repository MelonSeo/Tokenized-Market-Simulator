package com.pricetrack.exchange.ai.agent;

import static org.assertj.core.api.Assertions.*;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;
import static com.pricetrack.exchange.ai.agent.AgentFailure.EvidenceReason.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.tool.read.BuyAmountRelation;
import com.pricetrack.exchange.ai.tool.ToolFixtures;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentEvidenceReasonTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode input(){
        var n=json.createObjectNode();n.putArray("knowledge").addObject().put("id","policy");
        var data=n.putArray("tools").addObject().put("evidenceId","tool").putObject("data");
        data.set("amountRelation",BuyAmountRelation.quoted(json,ToolFixtures.quote(1,2)));
        return n;
    }
    Generated generated(String text,List<String> citations,String value){return new Generated("ANSWERED",text,citations,
        List.of(new FactReference("tool","/amountRelation/quotedTotalDebit",value)),List.of(),List.of(),Usage.none());}
    void rejected(Generated g,AgentFailure.EvidenceReason reason){
        assertThatThrownBy(()->AgentEvidence.validate(g,Route.MIXED,input())).isInstanceOfSatisfying(AgentFailure.class,e->{
            assertThat(e.code()).isEqualTo("INVALID_AGENT_EVIDENCE");assertThat(e.http()).isEqualTo(503);assertThat(e.evidenceReason()).isEqualTo(reason);
            assertThat(e.getMessage()).doesNotContain("75000","policy","tool");
        });
    }
    @Test void missingPolicyAndFeeContradictionStayDistinctWithoutRelaxingEither(){
        rejected(generated("입력에 수수료가 포함됩니다.",List.of("tool"),"75000.000000000000000000"),POLICY_CITATION_MISSING);
        rejected(generated("수수료 75가 별도로 차감됩니다.",List.of("policy","tool"),"75000.000000000000000000"),FEE_MEANING);
        // First failure precedence remains fee meaning, not the later missing-policy test.
        rejected(generated("수수료 75가 별도로 차감됩니다.",List.of("tool"),"75000.000000000000000000"),FEE_MEANING);
        assertThatCode(()->AgentEvidence.validate(generated("입력 75000 안에 수수료 75가 포함됩니다. 추가 차감하지 않습니다.",
            List.of("policy","tool"),"75000.000000000000000000"),Route.MIXED,input())).doesNotThrowAnyException();
    }
    @Test void exactScalarAndReadOnlyClaimsRemainMandatory(){
        rejected(generated("제공된 금액입니다.",List.of("policy","tool"),"75000"),FACT_VALUE);
        rejected(generated("재전송을 완료했습니다",List.of("policy","tool"),"75000.000000000000000000"),EXECUTION_CLAIM);
        rejected(generated("근거 설명",List.of("invented"),"75000.000000000000000000"),UNKNOWN_CITATION);
    }
    @Test void inactiveWaitingDefaultIsNotPresentedAsAnAppliedModelFact(){
        var data=json.createObjectNode().put("filter","REVIEW_REQUIRED").put("waitingSeconds",300).put("waitingSecondsApplied",false);
        var tool=AgentResponse.ToolEvidence.from("tool",com.pricetrack.exchange.ai.tool.ToolResult.success("listAbnormalOrders","TRADING_DB",data));
        var n=AgentEvidence.modelInput(json,List.of(),List.of(tool));
        assertThat(n.at("/tools/0/data/waitingSeconds").isMissingNode()).isTrue();
        assertThat(n.at("/tools/0/data/waitingSecondsApplied").isBoolean()).isTrue();
        assertThat(n.at("/tools/0/data/waitingSecondsApplied").booleanValue()).isFalse();
        assertThat(data.path("waitingSeconds").asInt()).isEqualTo(300); // public Tool evidence stays untouched
        var g=new Generated("ANSWERED","대기 기준",List.of("tool"),List.of(new FactReference("tool","/waitingSeconds","300")),List.of(),List.of(),Usage.none());
        assertThatThrownBy(()->AgentEvidence.validate(g,Route.MIXED,n)).isInstanceOfSatisfying(AgentFailure.class,e->assertThat(e.evidenceReason()).isEqualTo(FACT_VALUE));
    }
    @Test void appliedOrUnknownWaitingCriteriaAreNotSilentlyRemoved(){
        var data=json.createObjectNode().put("filter","WAITING_LONG").put("waitingSeconds",120).put("waitingSecondsApplied",true);
        var tool=AgentResponse.ToolEvidence.from("tool",com.pricetrack.exchange.ai.tool.ToolResult.success("listAbnormalOrders","TRADING_DB",data));
        assertThat(AgentEvidence.modelInput(json,List.of(),List.of(tool)).at("/tools/0/data/waitingSeconds").asInt()).isEqualTo(120);
        data.remove("waitingSecondsApplied");
        assertThat(AgentEvidence.modelInput(json,List.of(),List.of(tool)).at("/tools/0/data/waitingSeconds").asInt()).isEqualTo(120);
    }
    @Test void paginationIdentifiersStayPrivateButPageFactsAndOriginalDtoRemainIntact(){
        for(String kind:List.of("ABSENT","NULL","PRESENT")){
            var data=json.createObjectNode().put("hasMore",kind.equals("PRESENT")).put("scope","FIRST_PAGE_NOT_FULL_HISTORY");
            if(kind.equals("NULL"))data.putNull("nextCursor");
            if(kind.equals("PRESENT"))data.put("nextCursor","private-cursor-marker");
            data.putObject("nested").put("cursor","private-cursor-marker");
            var original=data.deepCopy();
            var tool=AgentResponse.ToolEvidence.from("tool",com.pricetrack.exchange.ai.tool.ToolResult.success("listAbnormalOrders","TRADING_DB",data));
            var n=AgentEvidence.modelInput(json,List.of(),List.of(tool));
            assertThat(n.at("/tools/0/data/nextCursor").isMissingNode()).isTrue();
            assertThat(n.toString()).doesNotContain("private-cursor-marker");
            assertThat(n.at("/tools/0/data/hasMore").booleanValue()).isEqualTo(kind.equals("PRESENT"));
            assertThat(n.at("/tools/0/data/scope").asText()).isEqualTo("FIRST_PAGE_NOT_FULL_HISTORY");
            assertThat(data).isEqualTo(original);
            var g=new Generated("ANSWERED","페이지 조회",List.of("tool"),List.of(new FactReference("tool","/nextCursor","null")),List.of(),List.of(),Usage.none());
            assertThatThrownBy(()->AgentEvidence.validate(g,Route.MIXED,n)).isInstanceOfSatisfying(AgentFailure.class,e->assertThat(e.evidenceReason()).isEqualTo(FACT_VALUE));
        }
    }
}
