package com.pricetrack.exchange.ai.pilot;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class PilotV2CTailChecksTest {
 final ObjectMapper j=new ObjectMapper();
 ObjectNode reply(){
  var n=j.createObjectNode();n.putNull("error");n.put("route","MIXED");n.put("status","PARTIAL");n.putObject("metrics").put("retrievalCalls",1);
  var a=n.putArray("knowledgeSources");for(int i=0;i<5;i++)a.addObject().put("path","c-vault-read-policy.md").put("role","USER");
  var d=n.putArray("toolEvidence").addObject().put("tool","getCAccount").put("status","SUCCESS").putObject("data");
  d.put("assetModel","C_ONCHAIN_VAULT_NOT_DB_LEDGER").put("deploymentId","fixture").put("personalClaimsEnumerated",false);d.putObject("observation").put("hash","0x"+"a".repeat(64)).put("scope","PINNED_RECHECKED");return n;
 }
 @Test void partialIsAllowedOnlyAsConnectionNotQualityPass(){assertThat(PilotV2CTailChecks.verify("PCa",reply(),"fixture")).containsEntry("realModelQuality","NOT_RUN").containsEntry("responseStatus","PARTIAL");}
 @Test void missingPinWrongAssetOrWrongPolicyCannotPass(){
  for(String kind:List.of("pin","asset","policy","claims")){var n=reply();switch(kind){case "pin"->((ObjectNode)n.at("/toolEvidence/0/data/observation")).remove("hash");case "asset"->((ObjectNode)n.at("/toolEvidence/0/data")).put("assetModel","LEGACY");case "policy"->((ObjectNode)n.at("/knowledgeSources/0")).put("role","ADMIN");case "claims"->((ObjectNode)n.at("/toolEvidence/0/data")).remove("personalClaimsEnumerated");}assertThatThrownBy(()->PilotV2CTailChecks.verify("PCa",n,"fixture")).isInstanceOf(IllegalStateException.class);}
 }
 @Test void cPositionNeedsHistoricalAndLockedValues(){var n=reply();((ObjectNode)n.at("/toolEvidence/0")).put("tool","getCPosition");assertThatThrownBy(()->PilotV2CTailChecks.verify("PCb",n,"fixture")).hasMessage("C_COLLATERAL_FIELDS_REQUIRED");}
 public static void main(String[] args){
  var l=new org.junit.platform.launcher.listeners.SummaryGeneratingListener();org.junit.platform.launcher.core.LauncherFactory.create().execute(org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request().selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(PilotV2CTailChecksTest.class)).build(),l);
  var s=l.getSummary();s.printTo(new java.io.PrintWriter(System.out,true));if(s.getTestsSucceededCount()!=3||s.getTotalFailureCount()!=0||s.getTestsSkippedCount()!=0)System.exit(1);
 }
}
