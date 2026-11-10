package com.pricetrack.exchange.futures.chain;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.pilot.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;

/** Test-classpath only, copied frozen trial only. No signing, fixture writes or public control API. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={"spring.config.import="})
@Import({FuturesAutoPostgresAnvilE2ETest.FixtureMarket.class,PilotV2TestConfiguration.class,PilotPaidServer.NoWriter.class})
public class PilotPaidServer extends PilotV2LiveHttpTest {
 @TestConfiguration static class NoWriter {
  @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor freezeWorker(){return b->{
   var d=(org.springframework.beans.factory.support.AbstractBeanDefinition)b.getBeanDefinition("futuresWorker");
   assertThat(d.getInitMethodName()).isEqualTo("start");d.setInitMethodName(null);
  };}
  @Bean static org.springframework.beans.factory.config.BeanPostProcessor stopWorker(){return new org.springframework.beans.factory.config.BeanPostProcessor(){
   @Override public Object postProcessBeforeInitialization(Object bean,String name){if(bean instanceof com.pricetrack.exchange.futures.runtime.FuturesWorker w)w.close();return bean;}
  };}
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=0||!"true".equals(System.getenv("AI_PILOT_FROZEN_SERVER")))throw new IllegalStateException("PAID_SERVER_OPT_IN");
  var l=new org.junit.platform.launcher.listeners.SummaryGeneratingListener();
  org.junit.platform.launcher.core.LauncherFactory.create().execute(org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request().selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(PilotPaidServer.class)).build(),l);
  var s=l.getSummary();
  new ObjectMapper().writeValue(Path.of(System.getenv("AI_PILOT_V2_FOLDER"),"server-junit.json").toFile(),Map.of("tests",s.getTestsFoundCount(),"passed",s.getTestsSucceededCount(),"skipped",s.getTestsSkippedCount(),"failed",s.getTotalFailureCount(),"causes",s.getFailures().stream().map(f->PilotV2FailureCodes.chain(f.getException())).toList()));
  System.exit(s.getTestsFoundCount()==1&&s.getTestsSucceededCount()==1&&s.getTestsSkippedCount()==0&&s.getTotalFailureCount()==0?0:1);
 }
 @Override @Test void completeOwnedFixtureFrozenScheduleAndRealSixDomSubmissions()throws Exception{
  assertThat(System.getProperty("os.name")).isEqualTo("Linux");
  folder=Path.of(System.getenv("AI_PILOT_V2_FOLDER"));session=folder.resolve("session");origin="http://127.0.0.1:"+port;
  var settings=json.readTree(Path.of(System.getenv("AI_PILOT_V2_CONFIG")).toFile());
  assertThat(settings.path("mode").asText()).isIn("UNPAID_CONTROLLED_ONLY","PAID_EXPLICIT_V2");
  assertThat(P.getProperty("app.ai.api-key")).isEmpty();
  var executor=(ExecutorService)org.springframework.test.util.ReflectionTestUtils.getField(worker,"executor");
  assertThat(executor.isShutdown()).isTrue();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
  var d=json.readTree(folder.resolve("prepared-descriptor.private.json").toFile());
  assertThat(d.path("recipe").path("scope").asText()).isEqualTo("NEW_ISOLATED_PILOT_V2_NOT_USER_DATA");
  actors.putAll(json.convertValue(d.path("recipe").path("actors"),Map.class));
  accounts.putAll(json.convertValue(d.path("accounts"),Map.class));
  for(String actor:List.of("owner","other","admin")){
   var r=request(null,"POST","/api/auth/login",accounts.get(actor));assertThat(r.status()).isEqualTo(200);
   tokens.put(actor,r.body().path("accessToken").asText());
  }
  var baseline=json.readTree(folder.resolve("baseline-fingerprint.json").toFile());
  frozenDb=baseline.path("dbAfter").asText();frozenC=baseline.path("cAfter").asText();
  assertThat(fingerprint()).isEqualTo(frozenDb);assertThat(cFingerprint()).isEqualTo(frozenC);
  assertThat(request(null,"GET","/api/me",null).status()).isEqualTo(401);
  for(String actor:List.of("owner","other")){
   String foreign=actor.equals("owner")?"other":"owner";
   var own=(Map<?,?>)actors.get(actor);var target=(Map<?,?>)actors.get(foreign);
   var q=request(actor,"POST","/api/ai/tools/getQuote",Map.of("arguments",Map.of("quoteId",own.get("quoteId"))));
   assertThat(q.status()).isEqualTo(200);assertThat(q.body().path("status").asText()).isEqualTo("SUCCESS");
   var denied=request(actor,"POST","/api/ai/tools/getQuote",Map.of("arguments",Map.of("quoteId",target.get("quoteId"))));
   assertThat(denied.status()).isEqualTo(404);assertThat(denied.body().path("error").asText()).isEqualTo("RESOURCE_NOT_FOUND");
   assertThat(request(actor,"GET","/api/c-futures/positions/"+target.get("cPositionId"),null).status()).isIn(403,404);
  }
  Path control=folder.resolve("control");Files.createDirectory(control);
  try{Files.setPosixFilePermissions(control,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
  save("server-ready.json",Map.of("authenticated",true,"foreignBlocked",true,"writerStopped",true,"mode",settings.path("mode").asText()));
  long end=System.nanoTime()+TimeUnit.HOURS.toNanos(2);boolean stop=false;
  while(!stop){
   if(System.nanoTime()>end)throw new IllegalStateException("PAID_SUPERVISOR_DEADLINE");
   List<Path> requests;try(var files=Files.list(control)){requests=files.filter(p->p.getFileName().toString().matches("request-[0-9a-f]{32}\\.json")).sorted().toList();}
   for(Path p:requests){
    Path reply=control.resolve(p.getFileName().toString().replace("request-","reply-"));if(Files.exists(reply))continue;
    assertThat(Files.isSymbolicLink(p)).isFalse();assertThat(Files.size(p)).isLessThanOrEqualTo(2_100_000);
    var f=json.readTree(p.toFile());Object value;
    try{value=switch(f.path("op").asText()){
     case "fingerprint"->Map.of("db",fingerprint(),"c",cFingerprint());
     case "budget"->budgetRows();
     case "pace"->{assertThat(f.path("actor").asText()).isIn("owner","other","admin");pace(f.path("actor").asText(),"PAID_ADAPTER");yield Map.of("capacityObserved",true);}
     case "verify"->{
      boolean valid=true;
      try{verifyReply(f.path("row"),f.path("reply"));}
       catch(AssertionError failure){if(!PilotV2QualityChecks.safeFallback(f.path("row"),f.path("reply"))
         &&!PilotV2QualityChecks.safeMissingRequiredTool(f.path("row"),f.path("reply")))throw failure;valid=false;}
      String id=f.path("row").path("case").asText();
      if(Set.of("PCa","PCb","PCc").contains(id))PilotV2CTailChecks.verify(id,f.path("reply"),request("owner","GET","/api/c-futures/config",null).body().at("/value/deploymentId").asText());
      yield valid?Map.of("transportVerified",true):Map.of("transportVerified",false,"quality","FAIL","safeReviewedContinuation",true);}
     case "shutdown"->{stop=true;yield Map.of("stopping",true);}
     default->throw new IllegalArgumentException("PAID_CONTROL_OPERATION");
    };saveControl(reply,Map.of("ok",true,"value",value));}
    catch(Exception | AssertionError failure){saveControl(reply,Map.of("ok",false,"code","PAID_CONTROL_CHECK_FAILED","causes",PilotV2FailureCodes.chain(failure)));throw failure;}
   }
   Thread.sleep(40);
  }
 }
 void saveControl(Path target,Object value)throws Exception{
  Path tmp=target.resolveSibling(target.getFileName()+".partial");
  Files.writeString(tmp,json.writeValueAsString(value),StandardOpenOption.CREATE_NEW);
  Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);
 }
}
