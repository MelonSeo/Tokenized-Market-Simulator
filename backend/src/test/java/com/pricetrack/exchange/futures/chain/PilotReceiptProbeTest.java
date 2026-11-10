package com.pricetrack.exchange.futures.chain;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.pilot.*;
import com.pricetrack.exchange.ai.tool.ToolDispatcher;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.wallet.WalletService;
import com.pricetrack.exchange.quote.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import java.math.BigDecimal;import java.net.*;import java.net.http.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.context.annotation.Import;import org.springframework.test.context.*;import org.springframework.jdbc.core.JdbcTemplate;
@EnabledIfEnvironmentVariable(named="AI_RECEIPT_PROBE",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={"spring.config.import="})
@Import({FuturesAutoPostgresAnvilE2ETest.FixtureMarket.class,PilotV2TestConfiguration.class})
class PilotReceiptProbeTest {
 static final Properties P=new Properties();
 @DynamicPropertySource static void config(DynamicPropertyRegistry r)throws Exception{try(var in=Files.newInputStream(Path.of(System.getenv("AI_PILOT_V2_APP")))){P.load(in);}P.forEach((k,v)->r.add(k.toString(),()->v));}
 @Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired AuthService auth;@Autowired WalletService wallets;@Autowired PriceQuoteService quotes;@Autowired OrderService orders;
 @Autowired(required=false) com.pricetrack.exchange.futures.runtime.FuturesWorker worker;
 @Autowired com.pricetrack.exchange.blockchain.reconciliation.BlockchainReconciliationService settlement;
 @Autowired ToolDispatcher dispatcher;@Autowired javax.sql.DataSource datasource;
 @Autowired(required=false) com.pricetrack.exchange.futures.ledger.FuturesLedger ledger;
 final HttpClient http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
 String fingerprint()throws Exception{var data=new TreeMap<String,Object>();for(String t:List.of("orders","trades","price_quotes","blockchain_transactions","user_balances","futures_positions","futures_operations","futures_journal","futures_transactions"))data.put(t,jdbc.queryForList("select * from "+t+" order by 1"));return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(data)));}
 Map<String,Object> resources()throws Exception{
  var pool=(ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(dispatcher,"workers");var m=new LinkedHashMap<String,Object>();m.put("toolActive",pool.getActiveCount());m.put("toolPool",pool.getPoolSize());m.put("toolCompleted",pool.getCompletedTaskCount());m.put("toolQueued",pool.getQueue().size());
  var h=(com.zaxxer.hikari.HikariDataSource)datasource;var db=h.getHikariPoolMXBean();m.put("dbActive",db.getActiveConnections());m.put("dbIdle",db.getIdleConnections());m.put("dbWaiting",db.getThreadsAwaitingConnection());
  var sockets=new ArrayList<String>();for(String file:List.of("/proc/net/tcp","/proc/net/tcp6"))for(String line:Files.readAllLines(Path.of(file))){var parts=line.trim().split("\\s+");if(parts.length>4&&parts[2].endsWith(":2161"))sockets.add(parts[1]+"/"+parts[2]+"/"+parts[3]+"/"+parts[4]);}m.put("processWideAnvilSocketMetadata",sockets);return m;
 }
 JsonNode post(String path,String jwt,Object body)throws Exception{var q=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080"+path)).timeout(java.time.Duration.ofSeconds(10)).header("Content-Type","application/json");if(jwt!=null)q.header("Authorization","Bearer "+jwt);var r=http.send(q.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofByteArray());if(path.endsWith("getReceiptSummary"))assertThat(r.statusCode()).isIn(200,503,504);else assertThat(r.statusCode()).isEqualTo(200);var result=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(r.body());result.put("_probeHttp",r.statusCode());return result;}
 @Test void narrowReceiptOnly()throws Exception{
  Path folder=Path.of(System.getenv("AI_PILOT_V2_FOLDER"));boolean clone="true".equals(System.getenv("AI_RECEIPT_CLONE"));
  if(!clone)ledger.initializeEmptyFixture();if(worker!=null){worker.close();var ex=(ExecutorService)org.springframework.test.util.ReflectionTestUtils.getField(worker,"executor");assertThat(ex.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
  String login,password;long oid;
  if(clone){var f=json.readTree(folder.resolve("probe-fixture.private.json").toFile());login=f.at("/account/loginId").asText();password=f.at("/account/password").asText();oid=f.path("orderId").asLong();assertThat(oid).isPositive();}
  else{
   login="receipt_"+UUID.randomUUID();password=UUID.randomUUID().toString();auth.signup(new AuthController.SignupRequest(login,password,"probe"));long uid=jdbc.queryForObject("select id from users where login_id=?",Long.class,login);wallets.faucet(uid,UUID.randomUUID().toString());
   var quote=quotes.issue(uid,"mSEC",PriceReport.Side.BUY,new BigDecimal("75000"));var order=orders.buy(uid,"mSEC",new BigDecimal("75000"),quote.getQuoteId());oid=order.getId();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
   while(orders.findOne(uid,oid).getStatus()!=OrderStatus.FILLED){if(System.nanoTime()>end)throw new IllegalStateException("RECEIPT_SEED_TIMEOUT");settlement.reconcilePendingTransactions();Thread.sleep(100);}
  }
  String jwt=post("/api/auth/login",null,Map.of("loginId",login,"password",password)).path("accessToken").asText();
  String before=fingerprint();var results=new ArrayList<Object>();
  try{for(int i=0;i<16;i++){
   if(i==8)Thread.sleep(65000);ReceiptProbeTrace.current.set(i+1);var beforeResource=resources();long n=System.nanoTime();
   var result=post("/api/ai/tools/getReceiptSummary",jwt,Map.of("arguments",Map.of("orderId",oid)));
   var row=new LinkedHashMap<String,Object>();row.put("call",i+1);row.put("elapsedMs",(System.nanoTime()-n)/1_000_000);row.put("reply",result);row.put("before",beforeResource);row.put("after",resources());results.add(row);
   var pool=(ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(dispatcher,"workers");long settle=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(pool.getActiveCount()>0&&System.nanoTime()<settle)Thread.sleep(10);
   row.put("settled",resources());ReceiptProbeTrace.flush(json,folder);Files.writeString(folder.resolve("receipt-calls.private.json"),json.writeValueAsString(results));assertThat(pool.getActiveCount()).isZero();
   assertThat(result.path("tool").asText()).isEqualTo("getReceiptSummary");
   if(result.path("status").asText().equals("SUCCESS")){assertThat(result.at("/data/receiptStatus").asText()).isEqualTo("FOUND");assertThat(result.at("/data/eventValidation").asText()).isEqualTo("MATCH");}
   else assertThat(result.path("error").asText()).isIn("TOOL_TIMEOUT","TOOL_UNAVAILABLE");
  }}finally{ReceiptProbeTrace.flush(json,folder);Files.writeString(folder.resolve("receipt-fingerprint.json"),json.writeValueAsString(Map.of("before",before,"after",fingerprint(),"unchanged",before.equals(fingerprint()))));assertThat(fingerprint()).isEqualTo(before);}
  assertThat(results).anyMatch(v->((JsonNode)((Map<?,?>)v).get("reply")).path("status").asText().equals("SUCCESS"));
  Files.writeString(folder.resolve("receipt-result.json"),json.writeValueAsString(Map.of("status","MEASURED_NOT_INTEGRATION_ACCEPTANCE","calls",results.size(),"paidCalls",0,"apiKeyReads",0,"financialUnchanged",true,"agentPath","NOT_RUN_DIRECT_AUTHENTICATED_TOOL")));
 }
 public static void main(String[] args){var l=new org.junit.platform.launcher.listeners.SummaryGeneratingListener();org.junit.platform.launcher.core.LauncherFactory.create().execute(org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request().selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(PilotReceiptProbeTest.class)).build(),l);var s=l.getSummary();s.printTo(new java.io.PrintWriter(System.out,true));s.printFailuresTo(new java.io.PrintWriter(System.out,true));System.exit(s.getTestsSucceededCount()==1&&s.getTestsSkippedCount()==0&&s.getTotalFailureCount()==0?0:1);}
}
