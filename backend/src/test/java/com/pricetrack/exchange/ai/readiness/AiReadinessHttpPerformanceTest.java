package com.pricetrack.exchange.ai.readiness;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.provider.*;
import com.pricetrack.exchange.ai.retrieval.*;
import com.pricetrack.exchange.ai.store.*;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.ai.tool.receipt.ReceiptReader;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.quote.*;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.wallet.*;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/** Real loopback HTTP/JWT/JPA/PG/RAG; controlled provider, synthetic vectors, no finance execution. */
@EnabledIfEnvironmentVariable(named="AI_READINESS_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","spring.config.import=","spring.datasource.url=jdbc:h2:mem:ai_readiness;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.hikari.maximum-pool-size=4","app.ai.enabled=true","app.ai.agent.enabled=true","app.ai.tools.enabled=true",
    "app.ai.skills.enabled=true","app.ai.diagnosis.enabled=false","app.ai.api-key=","app.blockchain.enabled=false",
    "app.blockchain.price-report.enabled=false","app.admin.password=","logging.level.com.pricetrack.exchange.ai.agent=ERROR"})
class AiReadinessHttpPerformanceTest {
    static final Properties CONFIG=new Properties();
    static {String file=System.getenv("AI_READINESS_RUN");if(file!=null)try(var in=Files.newInputStream(Path.of(file))){CONFIG.load(in);}catch(Exception e){throw new IllegalStateException("OWN_READINESS_CONFIG_REQUIRED");}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("app.ai.jdbc-url",()->CONFIG.getProperty("db.url"));r.add("app.ai.db-user",()->CONFIG.getProperty("db.user"));
        r.add("app.ai.db-password",()->CONFIG.getProperty("db.password"));
        r.add("app.ai.manifest",()->"../docs/ai/ingest-manifest-pilot.json");
    }
    @Autowired ObjectMapper json;@Autowired UserRepository users;@Autowired UserBalanceRepository balances;
    @Autowired OrderRepository orders;@Autowired PriceQuoteRepository quotes;@Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate tradeDb;@Autowired AgentService agent;@Autowired ToolDispatcher tools;
    @Autowired PgKnowledgeStore store;@Autowired KnowledgeLoader loader;@Autowired AiProperties ai;
    @LocalServerPort int port;
    @MockBean OpenAiProvider provider;@MockBean ReceiptReader receipts;
    final AtomicInteger embeddingCalls=new AtomicInteger(),modelCalls=new AtomicInteger(),entered=new AtomicInteger(),privacyViolations=new AtomicInteger();
    volatile String behavior="NORMAL";volatile Runnable synthesisHook=()->{};
    JsonNode plan;Path output;HttpClient http;ExecutorService clients;ScheduledExecutorService sampler;
    String ownerToken,otherToken,adminToken,quoteId;long ownerId,otherId;
    final Map<String,Object> result=new LinkedHashMap<>(),resource=new LinkedHashMap<>();
    final AtomicInteger peakAgent=new AtomicInteger(),peakQueue=new AtomicInteger(),peakTool=new AtomicInteger(),peakAiConnections=new AtomicInteger(),peakTradeConnections=new AtomicInteger();
    final AtomicLong peakHeap=new AtomicLong(),peakNonheap=new AtomicLong();long cpuBefore,cpuAfter;double sampleSeconds;
    final AtomicInteger sampleErrors=new AtomicInteger();
    final List<JsonNode> observedInputs=new CopyOnWriteArrayList<>();
    record Reply(int status,JsonNode body,double ms){}
    ThreadPoolExecutor agentPool(){return (ThreadPoolExecutor)ReflectionTestUtils.getField(agent,"workers");}
    HikariDataSource aiPool(){return (HikariDataSource)ReflectionTestUtils.getField(store,"pool");}
    HikariDataSource tradePool(){return (HikariDataSource)tradeDb.getDataSource();}
    static float[] vector(){var v=new float[1536];v[0]=1;return v;}
    static double quantile(List<Double> v,double q){var s=v.stream().sorted().toList();return s.isEmpty()?0:s.get(Math.max(0,(int)Math.ceil(s.size()*q)-1));}
    static double round(double x){return Math.round(x*1000)/1000.0;}
    static final Set<String> PRIVATE_INPUT_FIELDS=Set.of("userId","orderId","quoteId","txHash","senderAddress","executor","signature","nonce","rawTransaction",
        "loginId","password","passwordHash","privateKey","apiKey","apiSecret","secret","jwt","accessToken","refreshToken","dbPassword","id","positionId","operationId","journalId","pendingOperation","accountRef");
    void inspectModelData(JsonNode node){
        if(node.isObject())node.fields().forEachRemaining(e->{if(PRIVATE_INPUT_FIELDS.contains(e.getKey()))privacyViolations.incrementAndGet();inspectModelData(e.getValue());});
        else if(node.isArray())node.forEach(this::inspectModelData);
    }
    void delay(long ms,boolean cooperate)throws InterruptedException{
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);
        while(System.nanoTime()<end){try{Thread.sleep(Math.min(20,Math.max(1,TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime()))));}
            catch(InterruptedException e){if(cooperate)throw e;}}
    }
    void configureProvider(){
        when(provider.embed(anyList(),any())).thenAnswer(c->{embeddingCalls.incrementAndGet();delay(plan.path("controlledEmbeddingMs").asLong(),true);return ((List<?>)c.getArgument(0)).stream().map(x->vector()).toList();});
        when(provider.structured(anyString(),any(),anyString(),any(),anyInt(),any())).thenAnswer(c->{
            modelCalls.incrementAndGet();String kind=c.getArgument(0);Duration budget=c.getArgument(5);
            if(kind.equals("agent_plan")){
                delay(plan.path("controlledPlanningMs").asLong(),true);
                return new OpenAiProvider.Structured(json.createObjectNode().put("route","KNOWLEDGE").put("subject","NONE").put("skillId","NONE"),0,0);
            }
            var input=json.valueToTree(c.getArgument(3));var evidence=input.path("evidence");observedInputs.add(evidence.deepCopy());
            for(var k:evidence.path("knowledge"))if(!k.path("role").asText().equals("USER"))privacyViolations.incrementAndGet();
            for(var t:evidence.path("tools"))inspectModelData(t.path("data"));
            if(behavior.equals("MODEL_FAILURE"))throw new AiFailure("AI_PROVIDER_UNAVAILABLE");
            if(behavior.equals("GLOBAL_DEADLINE")){entered.incrementAndGet();delay(plan.path("nonCooperativeWorkMs").asLong(),false);}
            else if(behavior.equals("CLIENT_CANCEL")){entered.incrementAndGet();delay(600,true);}
            else delay(plan.path("controlledSynthesisMs").asLong(),true);
            synthesisHook.run();
            var out=json.createObjectNode().put("status","ANSWERED").put("interpretation","통제된 정책 근거와 현재 조회 사실만 표시합니다. 거래 실행이나 체결 확정은 하지 않습니다.");
            var cites=out.putArray("citationIds");cites.add(evidence.path("knowledge").get(0).path("id").asText());
            var facts=out.putArray("facts");
            if(!evidence.path("tools").isEmpty()){
                var t=evidence.path("tools").get(0);String key=t.path("data").has("storedStatus")?"storedStatus":t.path("data").has("filter")?"filter":t.path("data").has("provider")?"provider":"status";
                cites.add(t.path("evidenceId").asText());facts.addObject().put("evidenceId",t.path("evidenceId").asText()).put("pointer","/"+key).put("value",t.path("data").path(key).asText());
            }
            out.putArray("uncertainties");out.putArray("recommendedNextCheck");return new OpenAiProvider.Structured(out,0,0);
        });
        when(receipts.read(any())).thenReturn(json.createObjectNode().put("receiptStatus","NOT_FOUND"));
    }
    String login(String login,String password)throws Exception{
        var r=request(null,"/api/auth/login",Map.of("loginId",login,"password",password));assertThat(r.status).isEqualTo(200);
        return r.body.path("accessToken").asText(); // never written to results/assertion output
    }
    User user(String login,String password,UserRole role){var u=new User();u.setLoginId(login);u.setNickname(login);u.setPasswordHash(encoder.encode(password));u.setRole(role);return users.saveAndFlush(u);}
    Reply request(String token,String path,Object body)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(110)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        long start=System.nanoTime();var r=http.send(b.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(r.statusCode(),json.readTree(r.body()),(System.nanoTime()-start)/1e6);
    }
    Map<String,Object> body(String workload){return workload.equals("STATE")?Map.of("question","내 가용 잔고와 포트폴리오를 조회해줘"):
        workload.equals("MIXED")?Map.of("question","이 견적의 저장 상태와 정책을 설명해주세요","target",Map.of("quoteId",quoteId),"skillId","signed-quote-diagnosis"):
        Map.of("question","대기 상태의 의미는?");}
    Reply call(String workload)throws Exception{return request(ownerToken,"/api/ai/agent/answers",body(workload));}
    void assertSafe(Reply r){if(r.status==200){assertThat(r.body.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(r.body.toString()).doesNotContain(ownerToken,otherToken,adminToken);}}
    void assertNormal(String workload,Reply r){
        assertSafe(r);assertThat(r.status).isEqualTo(200);assertThat(r.body.path("error").isNull()).isTrue();
        assertThat(r.body.path("route").asText()).isEqualTo(workload);assertThat(r.body.path("status").asText()).isEqualTo("ANSWERED");
        assertThat(r.body.path("uncertainties")).isEmpty();
        if(workload.equals("STATE")){
            assertThat(r.body.path("metrics").path("modelCalls").asInt()).isZero();assertThat(r.body.path("toolEvidence")).hasSize(1);
            assertThat(r.body.path("toolEvidence").get(0).path("tool").asText()).isEqualTo("getPortfolio");
            assertThat(r.body.path("toolEvidence").get(0).path("data").toString()).contains("123.25").doesNotContain("987.75");
        }else{
            assertThat(r.body.path("knowledgeSources")).isNotEmpty();assertThat(r.body.path("indexVersion").asText()).isNotBlank();
            var knowledgeIds=new HashSet<String>();for(var k:r.body.path("knowledgeSources")){assertThat(k.path("role").asText()).isEqualTo("USER");knowledgeIds.add(k.path("id").asText());}
            var citations=new HashSet<String>();r.body.path("citationIds").forEach(c->citations.add(c.asText()));assertThat(citations).anyMatch(knowledgeIds::contains);
            assertThat(r.body.path("metrics").path("retrievalCalls").asInt()).isEqualTo(1);
            assertThat(r.body.path("metrics").path("modelCalls").asInt()).isEqualTo(workload.equals("KNOWLEDGE")?2:1);
            if(workload.equals("MIXED")){
                assertThat(r.body.path("skill").path("id").asText()).isEqualTo("signed-quote-diagnosis");
                var toolNames=new HashSet<String>();var toolIds=new HashSet<String>();for(var t:r.body.path("toolEvidence")){assertThat(t.path("status").asText()).isEqualTo("SUCCESS");toolNames.add(t.path("tool").asText());toolIds.add(t.path("evidenceId").asText());}
                assertThat(toolNames).contains("getQuote","getOrder","getBlockchainTransaction");assertThat(citations).anyMatch(toolIds::contains);
                assertThat(r.body.path("skill").path("trace")).anyMatch(t->t.path("stepId").asText().equals("SUMMARIZE")&&t.path("resultCode").asText().equals("VALIDATED"));
            }
        }
    }
    List<Reply> batch(String workload,int c)throws Exception{
        var latch=new CountDownLatch(1);var futures=new ArrayList<Future<Reply>>();
        for(int n=0;n<c;n++)futures.add(clients.submit(()->{latch.await();return call(workload);}));
        latch.countDown();var rows=new ArrayList<Reply>();for(var f:futures)rows.add(f.get(115,TimeUnit.SECONDS));return rows;
    }
    Map<String,Object> measure(String workload,int concurrency)throws Exception{
        var rows=new ArrayList<Reply>();long start=System.nanoTime();var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();long cpuStart=os.getProcessCpuTime();
        for(int n=0;n<plan.path("attemptsPerWorkloadAndLevel").asInt();n+=concurrency)rows.addAll(batch(workload,concurrency));
        double seconds=(System.nanoTime()-start)/1e9;var ok=rows.stream().filter(r->r.status==200).toList();var rejected=rows.stream().filter(r->r.status==503&&r.body.path("error").asText().equals("AGENT_BUSY")).toList();
        ok.forEach(r->assertNormal(workload,r));assertThat(ok.size()+rejected.size()).isEqualTo(rows.size());
        var times=ok.stream().map(Reply::ms).toList();var denied=rejected.stream().map(Reply::ms).toList();
        if(concurrency<=plan.path("lowLoadMaximumConcurrency").asInt()){
            assertThat(ok.size()/(double)rows.size()).isGreaterThanOrEqualTo(plan.path("lowLoadMinimumSuccessRatio").asDouble());
            assertThat(quantile(times,.95)).isLessThanOrEqualTo(plan.path(workload.equals("STATE")?"stateSuccessP95LimitMs":"otherSuccessP95LimitMs").asDouble());
        }
        var measured=new LinkedHashMap<String,Object>(Map.of("workload",workload,"concurrency",concurrency,"attempts",rows.size(),"successful",ok.size(),"rejected",rejected.size(),
            "successP50Ms",round(quantile(times,.5)),"successP95Ms",round(quantile(times,.95)),"rejectedP95Ms",round(quantile(denied,.95)),
            "attemptsPerSecond",round(rows.size()/seconds),"successPerSecond",round(ok.size()/seconds)));
        measured.put("normalizedProcessCpuPercent",round((os.getProcessCpuTime()-cpuStart)/1e9/seconds/Runtime.getRuntime().availableProcessors()*100));
        measured.put("heapUsedMiB",round(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()/1048576.0));
        measured.put("outsideAgentP50Ms",round(quantile(ok.stream().map(r->Math.max(0,r.ms-r.body.path("metrics").path("latencyMs").asDouble())).toList(),.5)));
        return measured;
    }
    void indexActive(boolean active)throws Exception{try(var c=DriverManager.getConnection(CONFIG.getProperty("db.url"),CONFIG.getProperty("db.user"),CONFIG.getProperty("db.password"));
        var s=c.prepareStatement("update ai.indexes set active=? where id=?")){s.setBoolean(1,active);s.setString(2,loader.load().fingerprint());s.executeUpdate();}}
    void ownedDocker(String action)throws Exception{
        String name=CONFIG.getProperty("container");assertThat(name).isEqualTo("tms-ai-readiness-"+CONFIG.getProperty("identity"));
        var inspect=new ProcessBuilder("docker","inspect",name).redirectErrorStream(true).start();var obj=json.readTree(inspect.getInputStream().readAllBytes()).get(0);
        assertThat(inspect.waitFor()).isZero();assertThat(obj.at("/Config/Labels/tms.ai-readiness.run").asText()).isEqualTo(CONFIG.getProperty("identity"));
        assertThat(obj.at("/Config/Image").asText()).isEqualTo(CONFIG.getProperty("image"));
        var p=new ProcessBuilder("docker",action,name).redirectErrorStream(true).start();p.getInputStream().readAllBytes();assertThat(p.waitFor()).isZero();
    }
    Map<String,List<Map<String,Object>>> financial(){var m=new TreeMap<String,List<Map<String,Object>>>();for(String t:List.of("orders","price_quotes","trades","blockchain_transactions","user_balances"))
        m.put(t,tradeDb.queryForList("select * from "+t+" order by "+(t.equals("price_quotes")?"quote_id":"id")));return m;}
    void sample(){try{
        peakAgent.accumulateAndGet(agentPool().getActiveCount(),Math::max);peakQueue.accumulateAndGet(agentPool().getQueue().size(),Math::max);
        var tp=(ThreadPoolExecutor)ReflectionTestUtils.getField(tools,"workers");peakTool.accumulateAndGet(tp.getActiveCount(),Math::max);
        if(aiPool().getHikariPoolMXBean()!=null)peakAiConnections.accumulateAndGet(aiPool().getHikariPoolMXBean().getActiveConnections(),Math::max);
        if(tradePool().getHikariPoolMXBean()!=null)peakTradeConnections.accumulateAndGet(tradePool().getHikariPoolMXBean().getActiveConnections(),Math::max);
        peakHeap.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max);
        peakNonheap.accumulateAndGet(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed(),Math::max);
    }catch(RuntimeException observationFailure){sampleErrors.incrementAndGet();}}
    void idle()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(plan.path("cleanupDeadlineSeconds").asInt());
        while(agentPool().getActiveCount()!=0&&System.nanoTime()<end)Thread.sleep(25);
        assertThat(agentPool().getActiveCount()).isZero();assertThat(agentPool().getQueue()).isEmpty();}
    void security()throws Exception{
        assertThat(request(null,"/api/ai/provider-budget/initialize",Map.of()).status).isEqualTo(401);
        assertThat(request(ownerToken,"/api/ai/provider-budget/initialize",Map.of()).status).isEqualTo(403);
        var unapproved=request(adminToken,"/api/ai/provider-budget/initialize",Map.of());assertThat(unapproved.status).isEqualTo(503);assertThat(unapproved.body.path("code").asText()).isEqualTo("AI_BUDGET_NOT_CONFIGURED");
        int before=modelCalls.get();assertThat(request(null,"/api/ai/agent/answers",body("STATE")).status).isEqualTo(401);
        assertThat(request(ownerToken,"/api/ai/tools/listAbnormalOrders",Map.of("arguments",Map.of())).status).isEqualTo(403);
        assertThat(request(adminToken,"/api/ai/tools/listAbnormalOrders",Map.of("arguments",Map.of())).status).isEqualTo(200);
        var waiting=request(adminToken,"/api/ai/tools/listAbnormalOrders",Map.of("arguments",Map.of("filter","WAITING_LONG","limit",5,"waitingSeconds",120)));
        assertThat(waiting.status).isEqualTo(200);assertThat(waiting.body.path("data").path("filter").asText()).isEqualTo("WAITING_LONG");assertThat(waiting.body.path("data").path("limit").asInt()).isEqualTo(5);assertThat(waiting.body.path("data").path("waitingSeconds").asInt()).isEqualTo(120);
        assertThat(waiting.body.path("data").path("scope").asText()).isEqualTo("FIRST_PAGE_NOT_FULL_HISTORY");
        assertThat(request(ownerToken,"/api/ai/tools/getOrder",Map.of("arguments",Map.of("orderId",0))).status).isEqualTo(400);
        var foreign=request(otherToken,"/api/ai/agent/answers",body("MIXED"));var missingBody=new HashMap<>(body("MIXED"));missingBody.put("target",Map.of("quoteId","0x"+"ff".repeat(32)));
        var missing=request(otherToken,"/api/ai/agent/answers",missingBody);assertThat(foreign.status).isEqualTo(404);assertThat(missing.status).isEqualTo(404);
        assertThat(foreign.body.path("error")).isEqualTo(missing.body.path("error"));assertThat(modelCalls.get()).isEqualTo(before);
        var owner=call("STATE");var other=request(otherToken,"/api/ai/agent/answers",body("STATE"));
        assertThat(owner.body.path("toolEvidence").get(0).path("data").toString()).contains("123.25").doesNotContain("987.75");
        assertThat(other.body.path("toolEvidence").get(0).path("data").toString()).contains("987.75").doesNotContain("123.25");
        result.put("security",Map.of("authentication",true,"adminBoundary",true,"invalidInput",true,"foreignMissingIndistinguishable",true,"ownPortfolioIsolation",true));
    }
    void cache()throws Exception{
        var times=new LinkedHashMap<String,Object>();var corpus=loader.load();List<String> acrossModes=null;
        for(boolean on:List.of(false,true)){
            var cache=new QueryVectorCache(provider,ai.embeddingModel(),()->corpus.fingerprint());cache.enabled(on);
            var retriever=new AuthorizedKnowledgeRetrieval(ai,loader,store,cache);var actor=new com.pricetrack.exchange.auth.AuthenticatedUser(ownerId,null,UserRole.USER);
            retriever.search(actor,"대기 상태의 의미는?",Duration.ofSeconds(15));int before=embeddingCalls.get();var elapsed=new ArrayList<Double>();List<String> expected=null;
            for(int i=0;i<plan.path("cacheRepeatedSamples").asInt();i++){
                long start=System.nanoTime();var e=retriever.search(actor,"대기 상태의 의미는?",Duration.ofSeconds(15));elapsed.add((System.nanoTime()-start)/1e6);
                var ids=e.hits().stream().map(KnowledgeHit::id).toList();if(expected==null)expected=ids;else assertThat(ids).isEqualTo(expected);
                if(acrossModes==null)acrossModes=ids;else assertThat(ids).isEqualTo(acrossModes);
                assertThat(e.hits()).allMatch(h->h.role().equals("USER"));
            }
            int calls=embeddingCalls.get()-before;assertThat(calls).isEqualTo(on?0:plan.path("cacheRepeatedSamples").asInt());
            times.put(on?"warm":"cold",Map.of("samples",elapsed.size(),"embeddingCalls",calls,"p50Ms",round(quantile(elapsed,.5)),"p95Ms",round(quantile(elapsed,.95))));
        }
        double cold=((Number)((Map<?,?>)times.get("cold")).get("p50Ms")).doubleValue(),warm=((Number)((Map<?,?>)times.get("warm")).get("p50Ms")).doubleValue();
        assertThat(warm-cold).isLessThanOrEqualTo(plan.path("cacheWarmMedianRegressionLimitMs").asDouble());times.put("scope","RAG_COMPONENT");result.put("cache",times);
    }
    void faults()throws Exception{
        var rows=new ArrayList<Object>();result.put("faults",rows);
        behavior="MODEL_FAILURE";var failed=call("MIXED");assertThat(failed.status).isEqualTo(200);assertThat(failed.body.path("status").asText()).isEqualTo("PARTIAL");
        rows.add(Map.of("name","MODEL_FAILURE","http",failed.status,"elapsedMs",round(failed.ms),"factsPreserved",!failed.body.path("toolEvidence").isEmpty()));behavior="NORMAL";
        synthesisHook=()->{try{indexActive(false);}catch(Exception e){throw new IllegalStateException(e);}};
        try{var changed=call("MIXED");assertThat(changed.body.path("knowledgeSources")).isEmpty();assertThat(changed.body.path("citationIds")).isEmpty();
            assertThat(changed.body.path("uncertainties").toString()).contains("KNOWLEDGE_APPROVAL_UNVERIFIED");rows.add(Map.of("name","INDEX_CHANGE","http",changed.status,"elapsedMs",round(changed.ms),"policyHidden",true));}
        finally{synthesisHook=()->{};indexActive(true);}
        behavior="CLIENT_CANCEL";entered.set(0);var canceled=clients.submit(()->call("MIXED"));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(entered.get()==0&&System.nanoTime()<until)Thread.sleep(10);assertThat(entered.get()).isEqualTo(1);canceled.cancel(true);idle();
        rows.add(Map.of("name","CLIENT_CANCEL","boundedCleanup",true));behavior="NORMAL";
        behavior="GLOBAL_DEADLINE";entered.set(0);var a=clients.submit(()->call("MIXED"));var b=clients.submit(()->call("MIXED"));until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(entered.get()<2&&System.nanoTime()<until)Thread.sleep(10);assertThat(entered.get()).isEqualTo(2);
        var busy=batch("STATE",8);assertThat(busy).allMatch(r->r.status==503&&r.body.path("error").asText().equals("AGENT_BUSY"));
        var ra=a.get(110,TimeUnit.SECONDS);var rb=b.get(110,TimeUnit.SECONDS);
        for(var r:List.of(ra,rb)){assertThat(r.status).isEqualTo(504);assertThat(r.ms).isBetween(plan.path("globalTimeoutMinimumMs").asDouble(),plan.path("globalTimeoutMaximumMs").asDouble());}
        assertThat(agentPool().getActiveCount()).isEqualTo(2);assertThat(call("STATE").status).isEqualTo(503);idle();behavior="NORMAL";assertThat(call("STATE").status).isEqualTo(200);
        rows.add(Map.of("name","GLOBAL_DEADLINE","samples",2,"firstMs",round(ra.ms),"secondMs",round(rb.ms),"busyRejected",busy.size(),"capacityRetainedUntilExit",true,"recovered",true));
        ownedDocker("pause");
        try{var down=call("MIXED");assertThat(down.status).isEqualTo(200);assertThat(down.body.path("status").asText()).isEqualTo("PARTIAL");assertThat(down.body.path("knowledgeSources")).isEmpty();
            assertThat(down.ms).isLessThanOrEqualTo(plan.path("databaseFailureMaximumMs").asDouble());rows.add(Map.of("name","AI_DB_PAUSED","http",down.status,"elapsedMs",round(down.ms),"policyHidden",true));}
        finally{ownedDocker("unpause");}idle();
    }
    void browser()throws Exception{
        if(!"true".equals(System.getenv("AI_PILOT_BROWSER")))return;
        Path config=output.resolve("browser-config.private.json");
        try{
            Files.writeString(config,json.writeValueAsString(Map.of("origin","http://127.0.0.1:"+port,"password",CONFIG.getProperty("browser.password"),"quoteId",quoteId)));
            var proc=new ProcessBuilder("node","../tools/websocket-test-client/scripts/ai-pilot-browser.mjs",config.toString(),output.resolve("browser").toString())
                .redirectErrorStream(true).redirectOutput(output.resolve("browser.private.log").toFile()).start();
            if(!proc.waitFor(180,TimeUnit.SECONDS)){proc.destroyForcibly();throw new IllegalStateException("OWN_BROWSER_TIMEOUT");}
            assertThat(proc.exitValue()).isZero();var observed=json.readTree(output.resolve("browser/result.json").toFile());assertThat(observed.path("passed").asBoolean()).isTrue();assertThat(observed.path("realModel").asBoolean()).isFalse();
            result.put("security",new LinkedHashMap<>((Map<String,Object>)result.get("security")));((Map<String,Object>)result.get("security")).put("chromeRealHttpControlledProvider",true);
        }finally{Files.deleteIfExists(config);}
    }
    @Test void readiness()throws Exception{
        output=Path.of(CONFIG.getProperty("output"));plan=json.readTree(Path.of("../deployment/ai-readiness-plan.json").toFile());
        var canonical=json.readTree(Path.of("../deployment/ai-readiness-plan.json").toFile());
        // Same canonical hash as Python: sorted keys, compact separators.
        var sorted=json.convertValue(canonical,new com.fasterxml.jackson.core.type.TypeReference<TreeMap<String,Object>>(){});
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(sorted)));
        result.put("schema",1);result.put("baseCommit",plan.path("baseCommit").asText());result.put("planSha256",hash);result.put("mode","CONTROLLED_NO_PAID_PROVIDER");
        result.put("environment",Map.of("javaFeature",Runtime.version().feature(),"osFamily",System.getProperty("os.name").startsWith("Windows")?"Windows":"Linux","processors",Runtime.getRuntime().availableProcessors(),"corpusDocuments",10,"corpusChunks",93));
        result.put("load",new ArrayList<>());result.put("cache",Map.of());result.put("faults",new ArrayList<>());result.put("security",Map.of());result.put("resources",resource);result.put("invariants",Map.of());result.put("verdict","FAIL");
        clients=Executors.newFixedThreadPool(10);http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();configureProvider();
        try{
            String password=UUID.randomUUID().toString();CONFIG.setProperty("browser.password",password);var owner=user("ready-owner",password,UserRole.USER);var other=user("ready-other",password,UserRole.USER);var admin=user("ready-admin",password,UserRole.ADMIN);
            ownerId=owner.getId();otherId=other.getId();ownerToken=login(owner.getLoginId(),password);otherToken=login(other.getLoginId(),password);adminToken=login(admin.getLoginId(),password);
            for(var pair:List.of(Map.entry(ownerId,"123.25"),Map.entry(otherId,"987.75"))){var balance=new UserBalance(pair.getKey(),"mKRW");balance.setAmount(new BigDecimal(pair.getValue()));balances.saveAndFlush(balance);}
            var order=orders.saveAndFlush(com.pricetrack.exchange.ai.tool.ToolFixtures.order(ownerId));
            var quote=com.pricetrack.exchange.ai.tool.ToolFixtures.quote(ownerId,order.getId());quoteId=quote.getQuoteId();quotes.saveAndFlush(quote);
            var before=financial();var corpus=loader.load();assertThat(corpus.documents()).hasSize(10);assertThat(corpus.chunks()).hasSize(93);
            var embeddings=new HashMap<String,float[]>();for(var ch:corpus.chunks())embeddings.put(ch.id(),vector());store.initialize();store.publish(corpus,embeddings,ai.embeddingModel());
            assertThat(store.searchLegacyAssets(corpus.fingerprint(),vector(),40,.25,UserRole.USER,Duration.ofSeconds(5))).noneMatch(h->h.path().equals("c-vault-read-policy.md"));
            assertThat(store.searchForScope(corpus.fingerprint(),vector(),5,.25,UserRole.USER,Set.of("custody"),Duration.ofSeconds(5))).isNotEmpty().allMatch(h->h.path().equals("c-vault-read-policy.md")&&h.role().equals("USER"));
            assertThat(store.searchLegacyIdentifiers(corpus.fingerprint(),vector(),List.of("getCAccount"),UserRole.USER,Duration.ofSeconds(5))).isEmpty();
            assertThat(store.searchIdentifiers(corpus.fingerprint(),vector(),List.of("getCAccount"),UserRole.USER,Set.of("custody"),Duration.ofSeconds(5))).isNotEmpty();
            security();browser();var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();cpuBefore=os.getProcessCpuTime();long start=System.nanoTime();
            sampler=Executors.newSingleThreadScheduledExecutor();sampler.scheduleAtFixedRate(this::sample,0,25,TimeUnit.MILLISECONDS);
            var loads=new ArrayList<Object>();result.put("load",loads);
            for(String workload:List.of("STATE","KNOWLEDGE","MIXED")){for(int w=0;w<plan.path("warmupPerWorkload").asInt();w++)assertNormal(workload,call(workload));
                for(var concurrency:plan.path("concurrency")){loads.add(measure(workload,concurrency.asInt()));write();}}
            cache();faults();idle();assertThat(privacyViolations.get()).isZero();assertThat(sampleErrors.get()).isZero();assertThat(financial()).isEqualTo(before);
            cpuAfter=os.getProcessCpuTime();sampleSeconds=(System.nanoTime()-start)/1e9;
            assertThat(peakAgent.get()).isLessThanOrEqualTo(plan.path("agentWorkersMaximum").asInt());assertThat(peakQueue.get()).isZero();
            assertThat(peakTool.get()).isLessThanOrEqualTo(plan.path("toolWorkersMaximum").asInt());assertThat(peakAiConnections.get()).isLessThanOrEqualTo(2);assertThat(peakTradeConnections.get()).isLessThanOrEqualTo(4);
            assertThat(aiPool().getHikariPoolMXBean().getActiveConnections()).isZero();assertThat(tradePool().getHikariPoolMXBean().getActiveConnections()).isZero();
            result.put("invariants",Map.of("financialRowsUnchanged",true,"noUserAdminChunks",true,"newPaidCalls",0,"agentIdle",true,"borrowedDbConnectionsAfter",0));result.put("verdict","PASS");
        }finally{
            if(sampler!=null){sampler.shutdownNow();sampler.awaitTermination(5,TimeUnit.SECONDS);}clients.shutdownNow();clients.awaitTermination(5,TimeUnit.SECONDS);http.close();
            resource.putAll(Map.of("peakAgentWorkers",peakAgent.get(),"peakAgentQueue",peakQueue.get(),"peakToolWorkers",peakTool.get(),"peakAiConnections",peakAiConnections.get(),"peakTradeConnections",peakTradeConnections.get(),
                "peakHeapMiB",round(peakHeap.get()/1048576.0),"peakNonheapMiB",round(peakNonheap.get()/1048576.0),"normalizedProcessCpuPercent",sampleSeconds==0?0:round((cpuAfter-cpuBefore)/1e9/sampleSeconds/Runtime.getRuntime().availableProcessors()*100)));
            write();
        }
    }
    void write()throws Exception{Files.writeString(output.resolve("metrics.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));}
}
