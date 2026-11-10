package com.pricetrack.exchange.ai.pilot;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Evidence/connection gate, never a natural-language quality oracle. */
public final class PilotV2CTailChecks {
 private static void need(boolean b,String code){if(!b)throw new IllegalStateException(code);}
 public static Map<String,Object> verify(String id,JsonNode reply,String deployment){
  need(Set.of("PCa","PCb","PCc").contains(id),"C_CASE_REQUIRED");
  need(reply.path("error").isNull()&&reply.path("route").asText().equals("MIXED"),"C_ROUTE_OR_ERROR");
  need(reply.at("/metrics/retrievalCalls").isIntegralNumber()&&reply.at("/metrics/retrievalCalls").asInt()==1,"C_RAG_REQUIRED");
  need(reply.path("knowledgeSources").size()==5,"C_ALL_POLICY_CHUNKS_REQUIRED");
  for(var s:reply.path("knowledgeSources"))need(s.path("path").asText().equals("c-vault-read-policy.md")&&s.path("role").asText().equals("USER"),"C_POLICY_SCOPE_REQUIRED");
  String tool=id.equals("PCb")?"getCPosition":"getCAccount";JsonNode data=null;
  for(var t:reply.path("toolEvidence")){
   need(t.path("status").asText().equals("SUCCESS"),"C_TOOL_FAILED");
   need(t.path("tool").asText().equals(tool),"C_LEGACY_TOOL_MIXED");data=t.path("data");
  }
  need(data!=null&&data.path("assetModel").asText().equals("C_ONCHAIN_VAULT_NOT_DB_LEDGER")&&data.path("deploymentId").asText().equals(deployment),"C_ASSET_IDENTITY_REQUIRED");
  need(data.at("/observation/hash").asText().matches("0x[0-9a-fA-F]{64}")&&!data.at("/observation/scope").asText().isBlank(),"C_PINNED_OBSERVATION_REQUIRED");
  if(tool.equals("getCAccount"))need(data.path("personalClaimsEnumerated").isBoolean()&&!data.path("personalClaimsEnumerated").asBoolean(),"C_CLAIMS_SCOPE_REQUIRED");
  else need(data.hasNonNull("historicalCollateral")&&data.hasNonNull("lockedCollateral"),"C_COLLATERAL_FIELDS_REQUIRED");
  return Map.of("case",id,"tool",tool,"policyChunks",5,"assetModel","C_ONCHAIN_VAULT_NOT_DB_LEDGER","responseStatus",reply.path("status").asText(),"realModelQuality","NOT_RUN");
 }
}
