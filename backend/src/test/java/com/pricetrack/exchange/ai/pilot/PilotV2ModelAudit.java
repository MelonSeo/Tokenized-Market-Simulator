package com.pricetrack.exchange.ai.pilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.agent.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Test-classpath only. No generated text, scalar values, pointers, credentials or input bodies on disk. */
public final class PilotV2ModelAudit {
 private PilotV2ModelAudit(){}
 private static String hash(ObjectMapper j,Object x)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(j.writeValueAsBytes(x)));}
 public static ObjectNode describe(ObjectMapper j,AgentModelProvider.Generated g,AgentModelProvider.Route route,JsonNode input)throws Exception{
  var out=j.createObjectNode().put("generatedSha256",hash(j,g)).put("inputSha256",hash(j,input));
  try{AgentEvidence.validate(g,route,(ObjectNode)input);out.put("validation","PASS");}
  catch(AgentFailure failure){out.put("validation",failure.code());}
  out.put("generatedPresent",g!=null);
  if(g!=null){
   out.put("statusAllowed",Set.of("ANSWERED","INSUFFICIENT_EVIDENCE").contains(g.status()==null?"":g.status()))
      .put("interpretationPresent",g.interpretation()!=null&&!g.interpretation().isBlank())
      .put("interpretationWithinLimit",g.interpretation()!=null&&g.interpretation().length()<=8000)
      .put("factsPresent",g.facts()!=null).put("factsWithinLimit",g.facts()!=null&&g.facts().size()<=20)
      .put("citationsPresent",g.citationIds()!=null).put("citationsWithinLimit",g.citationIds()!=null&&g.citationIds().size()<=9)
      .put("uncertaintiesSafe",safeList(g.uncertainties())).put("nextChecksSafe",safeList(g.recommendedNextCheck()));
  }
  Set<String> policy=new HashSet<>();Map<String,JsonNode> tools=new HashMap<>();
  input.path("knowledge").forEach(n->policy.add(n.path("id").asText()));
  input.path("tools").forEach(n->tools.put(n.path("evidenceId").asText(),n.path("data")));
  var citations=g==null||g.citationIds()==null?List.<String>of():g.citationIds();
  out.put("citationCount",citations.size()).put("policyCited",citations.stream().anyMatch(policy::contains))
   .put("toolCited",citations.stream().anyMatch(tools::containsKey))
   .put("unknownCitationCount",citations.stream().filter(x->!policy.contains(x)&&!tools.containsKey(x)).count());
  var facts=out.putArray("facts");
  if(g!=null&&g.facts()!=null)for(var f:g.facts()){
   if(facts.size()>=20)break;
   var n=facts.addObject();boolean known=f!=null&&tools.containsKey(f.evidenceId());
   n.put("knownTool",known).put("citedTool",f!=null&&citations.contains(f.evidenceId()));
   String pointer=f==null?null:f.pointer();
   n.put("dataPrefix",pointer!=null&&pointer.startsWith("/data/"));
   boolean shape=pointer!=null&&pointer.startsWith("/")&&pointer.length()<=128;
   n.put("pointerShapeValid",shape);
   JsonNode value=j.getNodeFactory().missingNode();
   if(known&&shape)try{value=tools.get(f.evidenceId()).at(pointer);}catch(IllegalArgumentException ignored){}
   n.put("valueNode",value.isValueNode()).put("missing",value.isMissingNode())
    .put("exactValueMatch",f!=null&&value.isValueNode()&&value.asText().equals(f.value()));
  }
  return out;
 }
 private static boolean safeList(List<String> values){return values!=null&&values.size()<=8
   &&values.stream().allMatch(v->v!=null&&!v.isBlank()&&v.length()<=300);}
 public static void record(ObjectMapper j,AgentModelProvider.Generated g,AgentModelProvider.Route route,JsonNode input){
  try{
   var folder=Path.of(System.getenv("AI_PILOT_V2_FOLDER"));
   var raw=j.writeValueAsBytes(describe(j,g,route,input));
   if(raw.length>32768||Files.isSymbolicLink(folder))throw new IllegalStateException();
   var path=folder.resolve("model-validation-"+UUID.randomUUID()+".private.json");
   Files.createFile(path,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
   Files.write(path,raw,StandardOpenOption.WRITE);
  }catch(Exception error){throw new IllegalStateException("V2_MODEL_AUDIT_UNAVAILABLE");}
 }
}
