package com.pricetrack.exchange.ai.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.skill.*;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class FuturesAgentTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();final String id="11111111-1111-1111-1111-111111111111";
    final ToolDispatcher tools=mock(ToolDispatcher.class);final AgentModelProvider model=mock(AgentModelProvider.class);final AuthorizedKnowledgeRetrieval retrieval=mock(AuthorizedKnowledgeRetrieval.class);
    final AuthenticatedUser user=new AuthenticatedUser(7L,null,UserRole.USER);
    AgentService service(){return new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json,Duration.ofSeconds(40),new SkillProperties(true),new SkillRegistry(json));}
    byte[] request(String skill){return ("{\"question\":\"내 Long 포지션은 왜 청산됐나요\",\"target\":{\"positionId\":\""+id+"\"}"+skill+"}").getBytes(StandardCharsets.UTF_8);}
    com.fasterxml.jackson.databind.node.ObjectNode position(){return json.createObjectNode().put("id",id).put("status","LIQUIDATED").put("collateral","100000.000000000000000000")
        .put("riskStatus","GRACE").put("version",3).put("callEpoch",1).put("deadline",100).putNull("pendingOperation").put("realized","-100000.000000000000000000").put("badDebt","0.000000000000000000");}
    void setup(boolean verified)throws Exception {
        when(tools.invokeForAgent(any(),anyString(),any(),anyString(),any())).thenAnswer(c->{String name=c.getArgument(1);var node=json.createObjectNode();
            var position=position();
            if(name.equals("getFuturesPosition"))node.set("position",position);
            else {node.set("positionSnapshot",position);node.put("positionId",id).put("positionStatus","LIQUIDATED").put("verification",verified?"VERIFIED":"INCONCLUSIVE");node.putArray("operations").addObject().put("action","LIQUIDATE").put("verification",verified?"VERIFIED":"INCONCLUSIVE");}
            return ToolResult.success(name,"TRADING_DB+RPC",node);
        });
        var doc=new KnowledgeHit("policy","onchain-settlement-policy.md","선물","위험","3","USER","DB 담보, 유지증거금 5퍼센트, 60초 유예",.8);
        when(retrieval.searchScoped(any(),any(),any(),any())).thenReturn(new AuthorizedKnowledgeRetrieval.Evidence("index",List.of(doc)));
        when(retrieval.search(any(),any(),any())).thenReturn(new AuthorizedKnowledgeRetrieval.Evidence("index",List.of(doc)));
        when(model.synthesize(anyString(),any(),any(),any())).thenReturn(new Generated("ANSWERED","조회 근거와 정책을 구분해 설명합니다.",List.of("policy","tool-2"),List.of(new FactReference("tool-2","/verification",verified?"VERIFIED":"INCONCLUSIVE")),List.of(),List.of(),Usage.none()));
    }
    @Test void skillReusesTwoCallsScopesRagAndKeepsIdentifiersOutOfModel()throws Exception {
        setup(true);try(var s=service()){
            var result=s.answer(user,request(",\"skillId\":\"futures-liquidation-diagnosis\""));
            assertThat(result.status()).isEqualTo("ANSWERED");assertThat(result.skill().diagnosis().classification()).isEqualTo("FUTURES_LIQUIDATION_VERIFIED");assertThat(result.skill().trace()).hasSize(5);assertThat(result.metrics().toolCalls()).isEqualTo(2);
            verify(retrieval).searchScoped(eq(user),anyString(),any(),eq(Set.of("trading","settlement","support")));
            verify(model).synthesize(anyString(),eq(Route.MIXED),argThat(n->!n.toString().contains(id)),any());verify(model,never()).plan(any(),any(),any());
        }
    }
    @Test void missingEvidenceStaysPartialAndDoesNotInventConfirmedLiquidation()throws Exception {
        setup(false);try(var s=service()){var result=s.answer(user,request(",\"skillId\":\"futures-liquidation-diagnosis\""));assertThat(result.status()).isEqualTo("PARTIAL");assertThat(result.skill().diagnosis().classification()).isEqualTo("FUTURES_EVIDENCE_UNVERIFIED");assertThat(result.uncertainties()).contains("FUTURES_EVIDENCE_UNVERIFIED");verify(model,never()).synthesize(any(),any(),any(),any());}
    }
    @Test void foreignTargetCannotReachModelOrRag() {
        when(tools.invokeForAgent(any(),any(),any(),any(),any())).thenReturn(ToolResult.failure("getFuturesPosition","RESOURCE_NOT_FOUND"));
        try(var s=service()){assertThat(s.answer(user,request("")).httpStatus()).isEqualTo(404);verifyNoInteractions(model,retrieval);}
    }
    @Test void crossingToolVersionChangeBlocksBothSkillAndOrdinaryMixedSynthesis()throws Exception {
        for(boolean skill:List.of(true,false)){
            reset(tools,model,retrieval);setup(true);
            when(tools.invokeForAgent(any(),eq("getFuturesPosition"),any(),any(),any())).thenAnswer(c->{
                var position=position().put("status","OPEN").put("version",2);
                return ToolResult.success("getFuturesPosition","TRADING_DB",json.createObjectNode().set("position",position));
            });
            when(tools.invokeForAgent(any(),eq("getFuturesLiquidationEvidence"),any(),any(),any())).thenAnswer(c->{
                var node=json.createObjectNode().put("positionId",id).put("positionStatus","OPEN").put("verification","VERIFIED");node.set("positionSnapshot",position().put("status","OPEN"));
                node.putArray("operations").addObject().put("action","START_CALL").put("verification","VERIFIED");return ToolResult.success("getFuturesLiquidationEvidence","TRADING_DB+RPC",node);
            });
            when(model.planWithSkills(any(),any(),any(),any())).thenReturn(new Plan(Route.MIXED,Subject.FUTURES,Usage.none()));
            try(var s=service()){var r=s.answer(user,request(skill?",\"skillId\":\"futures-liquidation-diagnosis\"":""));assertThat(r.status()).isEqualTo("PARTIAL");assertThat(r.uncertainties()).contains("FUTURES_CUT_CHANGED");verify(model,never()).synthesize(any(),any(),any(),any());}
        }
    }
    @Test void stableSnapshotFieldsAreComparedIndependently() {
        var p=position();var evidence=json.createObjectNode();evidence.set("positionSnapshot",p.deepCopy());assertThat(FuturesEvidenceConsistency.samePosition(p,evidence)).isTrue();
        for(String field:List.of("version","collateral","riskStatus","callEpoch","deadline","pendingOperation","realized","badDebt")){
            var changed=p.deepCopy().put(field,"changed");evidence.set("positionSnapshot",changed);assertThat(FuturesEvidenceConsistency.samePosition(p,evidence)).as(field).isFalse();
        }
    }
    @Test void explicitTargetParsingMutationAndConflictsRemainServerControlled() {
        var r=AgentRequest.parse(json,request(""));assertThat(r.targetKind()).isEqualTo(Subject.FUTURES);
        assertThat(new AgentRequest("강제청산해줘",null,null).mutationRequest()).isTrue();assertThat(new AgentRequest("왜 청산됐나요",null,null).mutationRequest()).isFalse();
        assertThat(new AgentRequest("포지션 "+UUID.randomUUID(),null,null,null,id).conflictingTarget()).isTrue();
        assertThat(new AgentRequest("포지션 "+id,null,null,null,id).modelQuestion()).doesNotContain(id);
    }
}
