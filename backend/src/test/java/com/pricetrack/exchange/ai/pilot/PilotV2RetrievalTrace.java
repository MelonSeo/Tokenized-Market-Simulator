package com.pricetrack.exchange.ai.pilot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.provider.ProviderAccess;
import com.pricetrack.exchange.ai.store.*;
import com.pricetrack.exchange.user.UserRole;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Private test observer only. Never changes vector/score/filter/order or supplies answers. */
final class PilotV2RetrievalTrace extends PgKnowledgeStore {
 final ObjectMapper json;
 PilotV2RetrievalTrace(AiProperties p,ObjectMapper json){super(p);this.json=json;}
 static synchronized void write(ObjectMapper json,String kind,Map<String,Object> data){
  String folder=System.getenv("AI_PILOT_V2_FOLDER");if(folder==null)throw new AiFailure("AI_EVALUATION_TRACE_SCOPE");
  var event=new LinkedHashMap<String,Object>(data);event.put("kind",kind);event.put("requestId",ProviderAccess.actor().requestId());
  try{Files.writeString(Path.of(folder).resolve("retrieval.private.jsonl"),json.writeValueAsString(event)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
  catch(Exception e){throw new AiFailure("AI_EVALUATION_TRACE_WRITE_FAILED");}
 }
 List<KnowledgeHit> record(String mode,String f,float[] q,int k,Double threshold,UserRole role,Set<String> domains,List<KnowledgeHit> rows){
  var data=new LinkedHashMap<String,Object>();data.put("mode",mode);data.put("index",f);data.put("limit",k);data.put("minimumSimilarity",threshold);data.put("role",role);data.put("domains",domains);data.put("candidates",rows);
  try{data.put("vectorSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(PgKnowledgeStore.vector(q).getBytes(java.nio.charset.StandardCharsets.UTF_8))));}
  catch(Exception e){throw new AiFailure("AI_EVALUATION_TRACE_VECTOR");}
  write(json,"CANDIDATES",data);return rows;
 }
 @Override public List<KnowledgeHit> searchForRole(String f,float[] q,int k,double t,UserRole r,Duration d){return record("ROLE",f,q,k,t,r,null,super.searchForRole(f,q,k,t,r,d));}
 @Override public List<KnowledgeHit> searchForScope(String f,float[] q,int k,double t,UserRole r,Set<String> domains,Duration d){return record("SCOPE",f,q,k,t,r,domains,super.searchForScope(f,q,k,t,r,domains,d));}
 @Override public List<KnowledgeHit> searchLegacyAssets(String f,float[] q,int k,double t,UserRole r,Duration d){return record("LEGACY_ASSETS",f,q,k,t,r,null,super.searchLegacyAssets(f,q,k,t,r,d));}
 @Override public List<KnowledgeHit> searchIdentifiers(String f,float[] q,List<String> ids,UserRole r,Set<String> domains,Duration d){return record("IDENTIFIERS",f,q,40,null,r,domains,super.searchIdentifiers(f,q,ids,r,domains,d));}
 @Override public List<KnowledgeHit> searchLegacyIdentifiers(String f,float[] q,List<String> ids,UserRole r,Duration d){return record("LEGACY_IDENTIFIERS",f,q,40,null,r,null,super.searchLegacyIdentifiers(f,q,ids,r,d));}
}
