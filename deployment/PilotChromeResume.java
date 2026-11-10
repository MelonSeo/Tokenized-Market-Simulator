package com.pricetrack.exchange.futures.chain;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.ai.pilot.PilotV2Channel;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.*;import org.springframework.context.annotation.*;

/** UNPAID, copied owned trial only. No fixture creation, signing or phase fast-forward. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={"spring.config.import="})
@Import({FuturesAutoPostgresAnvilE2ETest.FixtureMarket.class,com.pricetrack.exchange.ai.pilot.PilotV2TestConfiguration.class,PilotChromeResume.NoWriter.class})
public class PilotChromeResume extends PilotV2LiveHttpTest {
 void proveBrowserSandbox()throws Exception{try(var profiles=Files.list(folder.resolve("browser"))){var profile=profiles.filter(p->p.getFileName().toString().startsWith("profile-")).findFirst().orElseThrow();String debugPort=Files.readAllLines(profile.resolve("DevToolsActivePort")).getFirst();assertThat(debugPort).matches("[0-9]{1,5}");process(List.of("node","/driver/ai-linux-browser-proof.mjs",debugPort,folder.resolve("browser/linux-sandbox.private.json").toString()),30,"sandbox-proof.private.log");}}
 static void verifyForeignQuote(int http,JsonNode body){assertThat(http).isEqualTo(404);assertThat(body.path("status").asText()).isEqualTo("ERROR");assertThat(body.path("error").asText()).isEqualTo("RESOURCE_NOT_FOUND");assertThat(body.path("data").isNull()).isTrue();}
 @TestConfiguration static class NoWriter {
  @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor freezeWorker(){return b->{
   var d=(org.springframework.beans.factory.support.AbstractBeanDefinition)b.getBeanDefinition("futuresWorker");
   assertThat(d.getInitMethodName()).isEqualTo("start");d.setInitMethodName(null);
  };}
  @Bean static org.springframework.beans.factory.config.BeanPostProcessor closeWorkerBeforeExposure(){return new org.springframework.beans.factory.config.BeanPostProcessor(){
   @Override public Object postProcessBeforeInitialization(Object bean,String name){if(bean instanceof com.pricetrack.exchange.futures.runtime.FuturesWorker worker)worker.close();return bean;}
  };}
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=0||!"true".equals(System.getenv("AI_PILOT_BROWSER_RESUME")))throw new IllegalStateException("CHROME_RESUME_OPT_IN");
  var l=new org.junit.platform.launcher.listeners.SummaryGeneratingListener();
  org.junit.platform.launcher.core.LauncherFactory.create().execute(org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request().selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(PilotChromeResume.class)).build(),l);
  var s=l.getSummary();s.printTo(new java.io.PrintWriter(System.out,true));s.printFailuresTo(new java.io.PrintWriter(System.out,true));
  System.exit(s.getTestsFoundCount()==1&&s.getTestsSucceededCount()==1&&s.getTestsSkippedCount()==0&&s.getTotalFailureCount()==0?0:1);
 }
 @Override @Test void completeOwnedFixtureFrozenScheduleAndRealSixDomSubmissions()throws Exception{
  folder=Path.of(System.getenv("AI_PILOT_V2_FOLDER"));session=folder.resolve("session");origin="http://127.0.0.1:"+port;
  assertThat(P.getProperty("app.ai.api-key")).isEmpty();
  var meta=json.readTree(session.resolve("session.json").toFile());assertThat(meta.path("mode").asText()).isEqualTo("UNPAID_CONTROLLED_ONLY");
  var executor=(ExecutorService)org.springframework.test.util.ReflectionTestUtils.getField(worker,"executor");assertThat(executor.isShutdown()).isTrue();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
  actors.putAll(json.convertValue(meta.path("fixture").path("actors"),Map.class));
  var browserConfig=json.readTree(folder.resolve("browser.private.json").toFile());
  for(String actor:List.of("owner","other")){
   var r=request(null,"POST","/api/auth/login",browserConfig.path("accounts").path(actor));assertThat(r.status()).isEqualTo(200);tokens.put(actor,r.body().path("accessToken").asText());
  }
  var baseline=json.readTree(folder.resolve("source-final-fingerprint.json").toFile());frozenDb=baseline.path("dbAfter").asText();frozenC=baseline.path("cAfter").asText();
  assertThat(fingerprint()).isEqualTo(frozenDb);assertThat(cFingerprint()).isEqualTo(frozenC);
  for(String actor:List.of("owner","other")){
   String foreign=actor.equals("owner")?"other":"owner";var target=(Map<?,?>)actors.get(foreign);
   var own=request(actor,"POST","/api/ai/tools/getQuote",Map.of("arguments",Map.of("quoteId",((Map<?,?>)actors.get(actor)).get("quoteId"))));assertThat(own.status()).isEqualTo(200);assertThat(own.body().path("status").asText()).isEqualTo("SUCCESS");
   var q=request(actor,"POST","/api/ai/tools/getQuote",Map.of("arguments",Map.of("quoteId",target.get("quoteId"))));
   verifyForeignQuote(q.status(),q.body());
   var p=request(actor,"GET","/api/c-futures/positions/"+target.get("cPositionId"),null);assertThat(p.status()).isIn(403,404);
  }
  var channel=channels.getObject();assertThat(channel.control(Map.of("op","active")).path("phase").asText()).isEqualTo("WEB_PILOT");
  assertThat(channel.control(Map.of("op","reconcile","pgRows",budgetRows())).path("matching").asBoolean()).isTrue();
  save("browser-ready.json",Map.of("ready",true));await("CHROME_HOST_RESOURCE_GUARD",60,()->Files.exists(folder.resolve("browser-admission.json")));
  assertThat(json.readTree(folder.resolve("browser-admission.json").toFile()).path("allowed").asBoolean()).isTrue();
  var rows=browserConfig.path("rows");assertThat(rows).hasSize(6);
  var pb=new ProcessBuilder("node","/driver/ai-linux-pilot-browser.mjs",folder.resolve("browser.private.json").toString(),folder.resolve("browser").toString()).directory(Path.of("..").toFile()).redirectError(folder.resolve("browser.private.log").toFile());
  var browser=pb.start();String ticket=null;int submitted=0;boolean proved=false;
  try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(browser.getInputStream()));var writer=new java.io.PrintWriter(browser.getOutputStream(),true)){
   String line;while((line=reader.readLine())!=null){var f=json.readTree(line);
    if(f.path("op").asText().equals("ready")){assertThat(proved).isFalse();proveBrowserSandbox();proved=true;
    }else if(f.path("op").asText().equals("before")){
     if(!proved){
      List<Path> profiles;try(var files=Files.list(folder.resolve("browser"))){profiles=files.filter(p->p.getFileName().toString().startsWith("profile-")).toList();}assertThat(profiles).hasSize(1);
      String debugPort=Files.readAllLines(profiles.getFirst().resolve("DevToolsActivePort")).getFirst();assertThat(debugPort).matches("[0-9]{1,5}");
      process(List.of("node","/driver/ai-linux-browser-proof.mjs",debugPort,folder.resolve("browser/linux-sandbox.private.json").toString()),30,"sandbox-proof.private.log");proved=true;
     }
     var row=rows.get(f.path("slot").asInt());pace(row.path("actor").asText());assertThat(f.path("body")).isEqualTo(row.path("body"));ticket=channel.control(Map.of("op","claim","phase","WEB_PILOT","slot",f.path("slot").asInt())).path("ticket").asText();
    }else if(f.path("op").asText().equals("after")){
     assertThat(f.at("/reply/http").asInt()).isEqualTo(200);verifyReply(rows.get(f.path("slot").asInt()),f.at("/reply/body"));channel.control(Map.of("op","complete","ticket",ticket,"serverRequest",f.at("/reply/body/runId").asText()));submitted++;
    }else if(f.path("op").asText().equals("stop")){
     save("browser-stop.json",Map.of("submitted",submitted,"failure",true));
     try(var profiles=Files.list(folder.resolve("browser"))){var profile=profiles.filter(p->p.getFileName().toString().startsWith("profile-")).findFirst().orElseThrow();String debugPort=Files.readAllLines(profile.resolve("DevToolsActivePort")).getFirst();process(List.of("node","/driver/ai-linux-browser-diagnose.mjs",debugPort,folder.resolve("browser/stop-diagnostic.private.json").toString()),15,"stop-diagnostic.private.log");}
    }else throw new IllegalStateException("CHROME_BROWSER_PROTOCOL");writer.println("{\"ok\":true}");
   }
  }finally{if(!browser.waitFor(10,TimeUnit.SECONDS))browser.destroyForcibly();}
  assertThat(browser.exitValue()).isZero();assertThat(submitted).isEqualTo(6);assertThat(proved).isTrue();
  assertThat(fingerprint()).isEqualTo(frozenDb);assertThat(cFingerprint()).isEqualTo(frozenC);assertThat(channel.control(Map.of("op","reconcile","pgRows",budgetRows())).path("matching").asBoolean()).isTrue();
  channel.control(Map.of("op","advance","evidenceReviewed",true));
  save("chrome-only-result.json",Map.of("status","PASS_UNPAID_CHROME_SIX_NOT_MODEL_QUALITY","browserRequests",6,"replayedPriorHttpRequests",0,"financialUnchanged",true,"cStateUnchanged",true,"budgetMatching",true,"sandboxVerified",true,"paidCalls",0));
 }
}
