package com.pricetrack.exchange.futures.chain;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PilotV2QualityChecksTest {
 @Test void missingToolIsRecordedFailButSecurityAndBudgetFailuresStillStop()throws Exception{
  var row=(ObjectNode)j.readTree("{\"case\":\"PA2a\",\"actor\":\"owner\"}");var r=reply();r.putArray("uncertainties");
  assertThat(PilotV2QualityChecks.safeMissingRequiredTool(row,r)).isTrue();
  var originalReply=r;assertThatThrownBy(()->new PilotV2LiveHttpTest().verifyReply(row,originalReply)).isInstanceOf(AssertionError.class).hasMessageContaining("getCurrentReferencePrice");
  ((ObjectNode)r.path("knowledgeSources").get(0)).put("role","ADMIN");assertThat(PilotV2QualityChecks.safeMissingRequiredTool(row,r)).isFalse();
  r=reply();r.putArray("uncertainties").add("AI_BUDGET_EXHAUSTED");assertThat(PilotV2QualityChecks.safeMissingRequiredTool(row,r)).isFalse();
  row.put("case","D5");assertThat(PilotV2QualityChecks.safeMissingRequiredTool(row,reply())).isFalse();
 }
 final ObjectMapper j=new ObjectMapper();
 ObjectNode reply()throws Exception{return (ObjectNode)j.readTree("{\"error\":null,\"status\":\"PARTIAL\",\"route\":\"MIXED\",\"answer\":\"조회 범위만 제공합니다.\",\"citationIds\":[],\"uncertainties\":[\"SYNTHESIS_UNAVAILABLE\"],\"indexVersion\":\"test\",\"knowledgeSources\":[{\"role\":\"USER\",\"path\":\"order-lifecycle.md\"}],\"toolEvidence\":[{\"tool\":\"listAbnormalOrders\",\"status\":\"SUCCESS\"}]}");}
 @Test void classificationKeepsFrozenAssertionFailed()throws Exception{
  var row=j.readTree("{\"case\":\"PC4b\",\"actor\":\"admin\"}");var r=reply();
  assertThat(PilotV2QualityChecks.safeFallback(row,r)).isTrue();
  assertThatThrownBy(()->new PilotV2LiveHttpTest().verifyReply(row,r)).isInstanceOf(AssertionError.class).hasMessageContaining("SYNTHESIS_UNAVAILABLE");
 }
 @Test void securityBudgetAndCTailFailuresCannotContinue()throws Exception{
  var row=(ObjectNode)j.readTree("{\"case\":\"PC4b\",\"actor\":\"owner\"}");var r=reply();
  ((ObjectNode)r.path("knowledgeSources").get(0)).put("role","ADMIN");assertThat(PilotV2QualityChecks.safeFallback(row,r)).isFalse();
  r=reply();((ObjectNode)r.path("toolEvidence").get(0)).put("status","ERROR");assertThat(PilotV2QualityChecks.safeFallback(row,r)).isFalse();
  r=reply();r.putArray("uncertainties").add("AI_BUDGET_UNAVAILABLE");assertThat(PilotV2QualityChecks.safeFallback(row,r)).isFalse();
  row.put("case","PCa");assertThat(PilotV2QualityChecks.safeFallback(row,reply())).isFalse();
 }
 @Test void d5ZeroModelAndRetrievalAssertionCannotBecomeQualityFailure()throws Exception{
  var row=j.readTree("{\"case\":\"D5\",\"actor\":\"owner\"}");var r=reply();
  r.putObject("metrics").put("modelCalls",1).put("retrievalCalls",1);
  assertThatThrownBy(()->new PilotV2LiveHttpTest().verifyReply(row,r)).isInstanceOf(AssertionError.class);
  assertThat(PilotV2QualityChecks.safeFallback(row,r)).isFalse();
 }
}
