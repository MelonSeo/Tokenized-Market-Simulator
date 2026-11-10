package com.pricetrack.exchange.ai.pilot;
import java.util.*;
/** Exception classes and known safe codes only, never messages/SQL/URLs/credentials. */
public final class PilotV2FailureCodes {
 public static List<Map<String,String>> chain(Throwable error){
  var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());var result=new ArrayList<Map<String,String>>();
  for(Throwable e=error;e!=null&&result.size()<8&&seen.add(e);e=e.getCause()){
   var row=new LinkedHashMap<String,String>();row.put("type",e.getClass().getName());
   String code=e instanceof com.pricetrack.exchange.ai.AiFailure a?a.code():e instanceof PilotFixtureWait.Failure f?f.code():null;
   if(code!=null&&code.matches("[A-Z][A-Z0-9_]{0,79}"))row.put("code",code);result.add(row);
  }return List.copyOf(result);
 }
}
