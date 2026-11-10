package com.pricetrack.exchange.ai.pilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.provider.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual PG quota component, NOT an Agent HTTP/model quality acceptance. No network sender or key reader. */
public final class PilotV2A2Probe {
 static final ObjectMapper J=new ObjectMapper().findAndRegisterModules();
 static final Path OUT=Path.of("/run/probe");static final long START=System.nanoTime();
 static void need(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
 static void event(String stage,Object data)throws Exception{
  Files.writeString(OUT.resolve("timeline.jsonl"),J.writeValueAsString(Map.of("stage",stage,"monotonicElapsedNs",System.nanoTime()-START,"observedJvmUtc",Instant.now().toString(),"data",data))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
 }
 static final class ControlledClock extends Clock {
  Instant now;long simulatedNs;int sleeps;boolean rollback;
  ControlledClock(Instant now,boolean rollback){this.now=now;this.rollback=rollback;}
  public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){need(z.equals(ZoneOffset.UTC),"A2_ZONE");return this;}public Instant instant(){return now;}
  void advance(long ms){now=now.plusMillis(ms);simulatedNs+=ms*1_000_000;}
  void sleep(long ms){advance(ms);if(++sleeps==1&&rollback)now=now.minusMillis(2384);}
 }
 static final byte[] EMB="{\"model\":\"text-embedding-3-small\",\"input\":[\"UNPAID_A2_QUOTA_PROBE\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
 static final byte[] LLM="{\"model\":\"gpt-5.6-terra\",\"service_tier\":\"default\",\"store\":false,\"max_output_tokens\":1600,\"input\":\"UNPAID_A2_QUOTA_PROBE\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
 static void reserve(PgProviderBudget budget,long actor,String id,String path,boolean a2)throws Exception{
  event("RESERVE_BEGIN",Map.of("actor",actor,"request",id,"kind",path,"a2",a2));
  try{
   String reservation=ProviderAccess.call(actor,id,()->budget.reserve(path,path.equals("responses")?LLM:EMB));
   event("RESERVE_COMMITTED",Map.of("actor",actor,"request",id,"reservation",reservation,"kind",path));
   // A counted in-memory supplier acknowledgement, no HTTP client/key/configuration exists here.
   budget.observed(reservation,J.readTree(path.equals("responses")?"{\"input_tokens\":1,\"output_tokens\":1}":"{\"prompt_tokens\":1}"));
  }catch(AiFailure e){event("RESERVE_REJECTED",Map.of("actor",actor,"request",id,"code",e.code()));throw e;}
 }
 static void seed(PgProviderBudget b,long owner,long admin,ControlledClock clock)throws Exception{
  String index=UUID.randomUUID().toString();for(int i=0;i<6;i++)reserve(b,admin,index,"embeddings",false);
  String[] cases={"PF1a","PF1b","PA2a","PA2b","PC4a","PC4b","A1"};
  for(String name:cases){long actor=name.startsWith("PC4")?admin:owner;String id=UUID.randomUUID().toString();event("HISTORY_REQUEST",Map.of("case",name,"actor",actor,"request",id));
   reserve(b,actor,id,"embeddings",false);for(int i=0;i<(Set.of("PF1a","A1").contains(name)?2:1);i++)reserve(b,actor,id,"responses",false);
   if(clock!=null)clock.advance(800);
  }
 }
 static AiProperties properties(Properties p){return new AiProperties(true,p.getProperty("jdbc"),p.getProperty("user"),p.getProperty("password"),".",".","","guarded:unpaid-a2/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);}
 public static void main(String[] args)throws Exception{
  need(args.length==0&&System.getProperty("os.name").equals("Linux")&&"true".equals(System.getenv("AI_A2_UNPAID_PROBE")),"A2_EXPLICIT_LINUX_ONLY");
  var p=new Properties();try(var in=Files.newInputStream(OUT.resolve("probe.private.properties"))){p.load(in);}
  need(p.getProperty("jdbc").matches("jdbc:postgresql://pg:5432/exchange_ai_a2_[a-f0-9]{16}\\?.*"),"A2_NEW_DATABASE_ONLY");
  var ai=properties(p);var limits=new PgProviderBudget.Limits("UNPAID_A2_NOT_APPROVAL",18_000_000_000L,18_000_000_000L,92,70,100,5);
  var results=new LinkedHashMap<String,Object>();
  try(var c=DriverManager.getConnection(ai.jdbcUrl(),ai.dbUser(),ai.dbPassword())){
   for(int scenario=0;scenario<3;scenario++){
    String kind=List.of("OLD_ONESHOT_CONTROLLED_ROLLBACK","RECHECK_CONTROLLED_ROLLBACK","RECHECK_REAL_CLOCK").get(scenario);event("SCENARIO_START",Map.of("scenario",kind));
    var controlled=scenario==2?null:new ControlledClock(Instant.now(),true);Clock clock=controlled==null?Clock.systemUTC():controlled;
    long owner=1+scenario*10,admin=3+scenario*10;
    try(var b=new PgProviderBudget(ai,limits,clock)){
     b.initialize();seed(b,owner,admin,controlled);var w=PilotV2Pacing.read(c,owner);need(w.count()==5,"A2_HISTORY_FIVE_REQUESTS");
     var before=clock.instant();long mono=System.nanoTime();
     if(scenario==0){long delay=PilotV2Pacing.waitMillis(w.count(),w.oldest(),before);event("OLD_WAIT",Map.of("requestedMillis",delay,"window",w,"authorityUtc",before));controlled.sleep(delay+1);}
     else PilotV2Pacing.await(()->PilotV2Pacing.read(c,owner),clock,controlled==null?System::nanoTime:()->controlled.simulatedNs,
       controlled==null?Thread::sleep:controlled::sleep,e->event("PACING",Map.of("scenario",kind,"clockKind",controlled==null?"REAL":"CONTROLLED_SIMULATED","timing",e)));
     event("A2_EXECUTE",Map.of("scenario",kind,"actor",owner,"authorityUtc",clock.instant(),"window",PilotV2Pacing.read(c,owner),"realWaitElapsedNs",System.nanoTime()-mono,"clockKind",controlled==null?"REAL":"CONTROLLED_SIMULATED"));
     String outcome="PASS";int senderCalls=0;
     try{reserve(b,owner,UUID.randomUUID().toString(),"embeddings",true);senderCalls++;}catch(AiFailure e){outcome=e.code();}
     need(scenario==0?outcome.equals("AI_ACCOUNT_RATE_LIMIT")&&senderCalls==0:outcome.equals("PASS")&&senderCalls==1,"A2_EXPECTED_QUOTA_RESULT");
     results.put(kind,Map.of("outcome",outcome,"mockSenderCalls",senderCalls,"clockKind",controlled==null?"REAL":"CONTROLLED_SIMULATED"));
    }
   }
   try(var q=c.createStatement();var r=q.executeQuery("select count(*),count(*) filter(where state='UNKNOWN') from ai_provider_budget.reservation")){r.next();need(r.getInt(1)==68&&r.getInt(2)==0,"A2_RESERVATION_COUNTS");}
   try(var q=c.createStatement();var r=q.executeQuery("select actor,created_at from ai_provider_budget.request order by created_at,actor")){while(r.next())event("PG_REQUEST_UTC",Map.of("actor",r.getLong(1),"createdAt",r.getTimestamp(2).toInstant()));}
  }
  Files.writeString(OUT.resolve("probe-result.json"),J.writeValueAsString(Map.of("status","PASS_PG_QUOTA_COMPONENT","scenarios",results,"agentHttp","NOT_RUN","paidCalls",0,"apiKeyReads",0)),StandardOpenOption.CREATE_NEW);
 }
}
