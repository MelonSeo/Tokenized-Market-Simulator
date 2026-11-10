package com.pricetrack.exchange.futures.chain;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
/** Classifies a recorded quality FAIL; never makes the frozen verifier PASS. */
public final class PilotV2QualityChecks {
 private PilotV2QualityChecks(){}
 public static boolean safeMissingRequiredTool(JsonNode row,JsonNode reply){
  String id=row.path("case").asText();String required=switch(id){
   case "PF1a"->"getOrder";case "PF1b"->"getQuote";case "PA2a","PA2b"->"getCurrentReferencePrice";
   case "PC4a","PC4b"->"listAbnormalOrders";default->null;};
  if(required==null||!reply.path("error").isNull()||!Set.of("ANSWERED","PARTIAL").contains(reply.path("status").asText())
    ||reply.path("answer").asText().isBlank())return false;
  for(var u:reply.path("uncertainties"))if(Set.of("PLAN_UNAVAILABLE","SYNTHESIS_UNAVAILABLE","RAG_UNAVAILABLE","KNOWLEDGE_APPROVAL_UNVERIFIED",
    "AGENT_CONTEXT_LIMIT","AI_ACCOUNT_RATE_LIMIT","AI_BUDGET_EXHAUSTED","AI_BUDGET_UNAVAILABLE").contains(u.asText()))return false;
  Set<String> tools=new HashSet<>();
  for(var t:reply.path("toolEvidence")){if(!t.path("status").asText().equals("SUCCESS"))return false;tools.add(t.path("tool").asText());}
  if(reply.at("/metrics/retrievalCalls").asInt()>0&&(reply.path("indexVersion").asText().isBlank()||reply.path("knowledgeSources").isEmpty()))return false;
  for(var s:reply.path("knowledgeSources"))if((!row.path("actor").asText().equals("admin")&&!s.path("role").asText().equals("USER"))
      ||s.path("path").asText().equals("c-vault-read-policy.md"))return false;
  return !tools.contains(required); // Frozen verifier still FAILs; review may not mark this PASS.
 }
 public static boolean safeFallback(JsonNode row,JsonNode reply){
  if(!reply.path("error").isNull()||!reply.path("status").asText().equals("PARTIAL")
    ||reply.path("uncertainties").size()!=1||!reply.path("uncertainties").get(0).asText().equals("SYNTHESIS_UNAVAILABLE")
    ||!reply.path("route").asText().equals("MIXED")||reply.path("citationIds").size()!=0
    ||reply.path("answer").asText().isBlank()||reply.path("toolEvidence").isEmpty()
    ||reply.path("indexVersion").asText().isBlank()||reply.path("knowledgeSources").isEmpty()
    ||Set.of("PCa","PCb","PCc","D1","D2","D3","D4","D5").contains(row.path("case").asText()))return false;
  Set<String> tools=new HashSet<>();
  for(var t:reply.path("toolEvidence")){if(!t.path("status").asText().equals("SUCCESS"))return false;tools.add(t.path("tool").asText());}
  for(var s:reply.path("knowledgeSources"))if((!row.path("actor").asText().equals("admin")&&!s.path("role").asText().equals("USER"))
     ||s.path("path").asText().equals("c-vault-read-policy.md"))return false;
  String required=switch(row.path("case").asText()){
   case "PF1a"->"getOrder";case "PF1b"->"getQuote";case "PA2a","PA2b"->"getCurrentReferencePrice";case "PC4a","PC4b"->"listAbnormalOrders";default->null;};
  return required==null||tools.contains(required);
 }
}
