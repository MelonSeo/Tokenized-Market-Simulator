package com.pricetrack.exchange.ai.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import java.util.*;
import static com.pricetrack.exchange.ai.agent.AgentFailure.EvidenceReason.*;

/** Builds model-safe evidence and validates references against immutable server facts. */
public final class AgentEvidence {
    private static final Set<String> PRIVATE_FIELDS=Set.of("userId","orderId","quoteId","txHash","senderAddress",
            "executor","signature","nonce","rawTransaction","loginId","password","passwordHash",
            "privateKey","apiKey","apiSecret","secret","jwt","accessToken","refreshToken","dbPassword",
            "id","positionId","operationId","journalId","pendingOperation","accountRef","nextCursor","cursor","owner","userNonce");
    public static ObjectNode modelInput(ObjectMapper json,List<KnowledgeHit> knowledge,List<AgentResponse.ToolEvidence> tools){
        ObjectNode out=json.createObjectNode();out.set("knowledge",json.valueToTree(knowledge));
        var array=out.putArray("tools");
        for(var tool:tools)if("SUCCESS".equals(tool.status())){
            var node=array.addObject().put("evidenceId",tool.evidenceId()).put("tool",tool.tool()).put("source",tool.source())
                    .put("retrievedAt",tool.retrievedAt().toString());
            var data=tool.data().deepCopy();removePrivate(data);node.set("data",data);
            // An unapplied default is configuration, not an observed waiting-time predicate.
            // Keep the original public Tool DTO unchanged and preserve the explicit false flag.
            if(tool.tool().equals("listAbnormalOrders")&&data instanceof ObjectNode object
                    &&data.path("waitingSecondsApplied").isBoolean()&&!data.path("waitingSecondsApplied").booleanValue())object.remove("waitingSeconds");
            if(tool.tool().equals("getPortfolio"))node.put("authorizationScope","AUTHENTICATED_SELF");
        }
        return out;
    }
    private static void removePrivate(JsonNode node){
        if(node.isObject()){
            var object=(ObjectNode)node;object.remove(PRIVATE_FIELDS);
            object.elements().forEachRemaining(AgentEvidence::removePrivate);
        }else if(node.isArray())node.elements().forEachRemaining(AgentEvidence::removePrivate);
    }
    public static void validate(AgentModelProvider.Generated generated,AgentModelProvider.Route route,ObjectNode input){
        if(generated==null || !Set.of("ANSWERED","INSUFFICIENT_EVIDENCE").contains(generated.status())
                || generated.interpretation()==null || generated.interpretation().isBlank() || generated.interpretation().length()>8000
                || generated.citationIds()==null || generated.citationIds().size()>9 || generated.facts()==null || generated.facts().size()>20
                || !safeList(generated.uncertainties()) || !safeList(generated.recommendedNextCheck())) invalid(STRUCTURE);
        if(!"ANSWERED".equals(generated.status()))return;
        if(BuyFeeMeaning.contradiction(generated.interpretation(),input))invalid(FEE_MEANING);
        Map<String,JsonNode> known=new HashMap<>();Set<String> knowledgeIds=new HashSet<>();Set<String> toolIds=new HashSet<>();
        for(var doc:input.path("knowledge")){known.put(doc.path("id").asText(),doc);knowledgeIds.add(doc.path("id").asText());}
        for(var tool:input.path("tools")){known.put(tool.path("evidenceId").asText(),tool.path("data"));toolIds.add(tool.path("evidenceId").asText());}
        if(generated.citationIds().isEmpty() || !known.keySet().containsAll(generated.citationIds()))invalid(UNKNOWN_CITATION);
        if(!knowledgeIds.isEmpty() && generated.citationIds().stream().noneMatch(knowledgeIds::contains))invalid(POLICY_CITATION_MISSING);
        if(route==AgentModelProvider.Route.MIXED && !toolIds.isEmpty()
                && (generated.citationIds().stream().noneMatch(toolIds::contains) || generated.facts().isEmpty()))invalid(TOOL_EVIDENCE_MISSING);
        for(var fact:generated.facts()){
            if(fact==null || !toolIds.contains(fact.evidenceId()) || !generated.citationIds().contains(fact.evidenceId())
                    || fact.pointer()==null || !fact.pointer().startsWith("/") || fact.pointer().length()>128 || fact.value()==null)invalid(FACT_REFERENCE);
            JsonNode value;
            try{value=known.get(fact.evidenceId()).at(fact.pointer());}catch(Exception e){invalid(FACT_REFERENCE);return;}
            // A recorded null (e.g. consumedAt) is a fact; an absent pointer is not.
            if(!value.isValueNode() || !value.asText().equals(fact.value()))invalid(FACT_VALUE);
        }
        if(route==AgentModelProvider.Route.KNOWLEDGE && !generated.facts().isEmpty())invalid(KNOWLEDGE_FACTS);
        // Execution claims have no supporting Tool in this read-only registry.
        if(generated.interpretation().matches("(?s).*(재전송|재서명|강제 정산|주문 생성|잔고 보정)(을|를)?\\s*(완료했|실행했|수행했).*"))invalid(EXECUTION_CLAIM);
    }
    private static boolean safeList(List<String> values){return values!=null && values.size()<=8
            && values.stream().allMatch(v -> v!=null && !v.isBlank() && v.length()<=300);}
    private static void invalid(AgentFailure.EvidenceReason reason){throw new AgentFailure("INVALID_AGENT_EVIDENCE",503,reason);}
}
