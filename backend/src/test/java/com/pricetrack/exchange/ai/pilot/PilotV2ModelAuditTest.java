package com.pricetrack.exchange.ai.pilot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.agent.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class PilotV2ModelAuditTest {
 @Test void invalidShapeHasSafeFlagsWithoutStoringGeneratedText()throws Exception{
  var j=new ObjectMapper();var input=j.createObjectNode();
  var g=new AgentModelProvider.Generated("PRIVATE_BAD_STATUS","SECRET_TEXT",List.of(),null,null,List.of(),null);
  var d=PilotV2ModelAudit.describe(j,g,AgentModelProvider.Route.MIXED,input);
  assertThat(d.path("validation").asText()).isEqualTo("INVALID_AGENT_EVIDENCE");
  assertThat(d.path("statusAllowed").asBoolean()).isFalse();assertThat(d.path("factsPresent").asBoolean()).isFalse();
  assertThat(d.path("uncertaintiesSafe").asBoolean()).isFalse();assertThat(d.toString()).doesNotContain("PRIVATE_","SECRET_");
 }
 @Test void mismatchedFactsAreDiagnosableWithoutValuesOrText()throws Exception{
  var j=new ObjectMapper();var input=j.readTree("{\"knowledge\":[{\"id\":\"policy\"}],\"tools\":[{\"evidenceId\":\"tool-1\",\"data\":{\"filter\":\"SECRET_ACTUAL_VALUE\"}}]}");
  var g=new AgentModelProvider.Generated("ANSWERED","SECRET_INTERPRETATION",List.of("policy","tool-1"),
   List.of(new AgentModelProvider.FactReference("tool-1","/data/filter","SECRET_MODEL_VALUE")),List.of(),List.of(),new AgentModelProvider.Usage(1,1));
  var d=PilotV2ModelAudit.describe(j,g,AgentModelProvider.Route.MIXED,input);
  assertThat(d.path("validation").asText()).isEqualTo("INVALID_AGENT_EVIDENCE");
  assertThat(d.at("/facts/0/dataPrefix").asBoolean()).isTrue();
  assertThat(d.at("/facts/0/exactValueMatch").asBoolean()).isFalse();
  assertThat(d.toString()).doesNotContain("SECRET_","/data/filter");
 }
 @Test void validFactsStillPassOriginalValidator()throws Exception{
  var j=new ObjectMapper();var input=j.readTree("{\"knowledge\":[{\"id\":\"p\"}],\"tools\":[{\"evidenceId\":\"t\",\"data\":{\"limit\":5}}]}");
  var g=new AgentModelProvider.Generated("ANSWERED","조회 한도 5",List.of("p","t"),List.of(new AgentModelProvider.FactReference("t","/limit","5")),List.of(),List.of(),new AgentModelProvider.Usage(1,1));
  assertThat(PilotV2ModelAudit.describe(j,g,AgentModelProvider.Route.MIXED,input).path("validation").asText()).isEqualTo("PASS");
 }
}
