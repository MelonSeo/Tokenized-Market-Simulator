package com.pricetrack.exchange.futures.chain;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.pilot.*;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.ai.tool.read.FuturesToolReader;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.auth.AuthController.SignupRequest;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.Action;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.futures.runtime.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.quote.*;
import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import com.pricetrack.exchange.wallet.WalletService;
import java.math.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

/** Real owned PG/Anvil/JWT/Agent plus controlled provider. Never a real-model quality assertion. */
@EnabledIfEnvironmentVariable(named="AI_PILOT_V2_HTTP_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={"spring.config.import="})
@Import({FuturesAutoPostgresAnvilE2ETest.FixtureMarket.class,PilotV2TestConfiguration.class})
class PilotV2LiveHttpTest {
    public static void main(String[] args)throws Exception{
        if(args.length!=0||!"true".equals(System.getenv("AI_PILOT_V2_HTTP_TESTS")))throw new IllegalStateException("V2_EXPLICIT_HTTP_OPT_IN");
        var listener=new org.junit.platform.launcher.listeners.SummaryGeneratingListener();
        org.junit.platform.launcher.core.LauncherFactory.create().execute(org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request().selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(PilotV2LiveHttpTest.class)).build(),listener);
        var s=listener.getSummary();
        var failures=s.getFailures().stream().map(f->Map.of("type",f.getException().getClass().getName(),"causes",PilotV2FailureCodes.chain(f.getException()),"frames",Arrays.stream(f.getException().getStackTrace()).filter(x->x.getClassName().equals(PilotV2LiveHttpTest.class.getName())).map(StackTraceElement::toString).toList())).toList();
        new ObjectMapper().writeValue(Path.of(System.getenv("AI_PILOT_V2_FOLDER")).resolve("standalone-"+UUID.randomUUID()+".json").toFile(),Map.of("tests",s.getTestsFoundCount(),"passed",s.getTestsSucceededCount(),"skipped",s.getTestsSkippedCount(),"aborted",s.getTestsAbortedCount(),"failed",s.getTotalFailureCount(),"failures",failures,"scope",System.getenv().getOrDefault("AI_PILOT_V2_HTTP_SCOPE","FULL")));
        s.printTo(new java.io.PrintWriter(System.out,true));
        System.exit(s.getTestsFoundCount()==1&&s.getTestsSucceededCount()==1&&s.getTestsSkippedCount()==0&&s.getTestsAbortedCount()==0&&s.getTotalFailureCount()==0?0:1);
    }
    static final Properties P=new Properties();
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r)throws Exception{
        try(var in=Files.newInputStream(Path.of(System.getenv("AI_PILOT_V2_APP")))){P.load(in);}
        assertThat(P.getProperty("spring.datasource.url")).contains("/exchange_ai_pilot_");P.forEach((k,v)->r.add(k.toString(),()->v));
    }
    @Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired AuthService auth;@Autowired WalletService wallets;
    @Autowired PriceQuoteService quotes;@Autowired OrderService orders;@Autowired FuturesLedger ledger;@Autowired FuturesService commands;
    @Autowired PriceQuoteRepository quoteRows;@Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @Autowired FuturesWorker worker;@Autowired FuturesToolReader futures;
    @Autowired FuturesAutoPostgresAnvilE2ETest.Scenario scenario;
    @Autowired com.pricetrack.exchange.blockchain.reconciliation.BlockchainReconciliationService settlement;
    @Autowired com.pricetrack.exchange.custody.auth.CWalletLinkStore walletLinks;
    @Autowired org.springframework.beans.factory.ObjectProvider<PilotV2Channel> channels;
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    Path folder,session;String origin,password,frozenDb,frozenC;final Map<String,String> tokens=new HashMap<>();final Map<String,Object> accounts=new LinkedHashMap<>(),actors=new LinkedHashMap<>();
    record Reply(int status,JsonNode body){}
    Reply request(String actor,String method,String path,Object body)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(110)).header("Content-Type","application/json");
        if(path.equals("/api/c-futures/wallet/challenges")||path.equals("/api/c-futures/wallet/link"))b.header("Origin",origin);
        if(actor!=null)b.header("Authorization","Bearer "+tokens.get(actor));
        if(method.equals("GET"))b.GET();else b.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        var r=http.send(b.build(),HttpResponse.BodyHandlers.ofByteArray());assertThat(r.body().length).isLessThanOrEqualTo(2_000_000);
        return new Reply(r.statusCode(),r.body().length==0?json.nullNode():json.readTree(r.body()));
    }
    void save(String name,Object value)throws Exception{Files.writeString(folder.resolve(name),json.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardOpenOption.CREATE_NEW);}
    void await(String code,int seconds,BooleanSupplier p)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!p.getAsBoolean()){if(System.nanoTime()>end)throw new IllegalStateException(code);Thread.sleep(100);}}
    @Autowired com.pricetrack.exchange.futures.runtime.FuturesMarketGate fixtureMarketGate;
    void awaitLiquidated(String pid,long uid)throws Exception{
        if(!"true".equals(System.getenv("AI_PILOT_NATIVE_BROWSER"))){await("V2_LEGACY_LIQUIDATION",100,()->ledger.position(pid,uid).status().equals("LIQUIDATED"));return;}
        var samples=new ArrayList<Object>();
        try(var rpc=new com.pricetrack.exchange.custody.runtime.CCustodyRpcClient(URI.create(P.getProperty("app.futures.rpc-url")))){
            PilotFixtureWait.await("V2_LEGACY_LIQUIDATION",Duration.ofSeconds(100),()->ledger.position(pid,uid).status().equals("LIQUIDATED"),worker::signal,()->{
                var row=new LinkedHashMap<String,Object>();row.put("utc",Instant.now().toString());row.put("workerFailures",worker.failureCount());
                try{
                    var p=ledger.position(pid,uid);var mark=fixtureMarketGate.capture();long chain=Long.parseLong(rpc.request("eth_getBlockByNumber",List.of("latest",false),1000).path("timestamp").asText().substring(2),16);
                    row.put("status",p.status());row.put("risk",p.riskStatus());row.put("deadline",p.deadline());row.put("observed",mark.observedAt().getEpochSecond());row.put("chain",chain);row.put("price",mark.price().toString());
                    row.put("replayedDecisionNotWorkerTrace",new com.pricetrack.exchange.futures.runtime.FuturesRiskEvaluator().evaluate(p,mark,fixtureMarketGate.now().getEpochSecond(),chain).map(Enum::name).orElse("NONE"));
                }catch(RuntimeException error){row.put("sampleErrorType",error.getClass().getSimpleName());}
                samples.add(row);
            },System::nanoTime,Thread::sleep);
        }finally{save("fixture-liquidation-"+uid+".private.json",Map.of("workerWakeupsByFixture",true,"productDeadlineUnchanged",true,"samples",samples));}
    }
    long signup(String role)throws Exception{
        boolean reuse="true".equals(System.getenv("AI_PILOT_V2_REUSE_TRIAL"));
        String login;
        if(reuse){var previous=jdbc.queryForList("select login_id,role from users where login_id like ?","v2_"+role+"_%");assertThat(previous).hasSize(1);assertThat(previous.getFirst().get("role")).isEqualTo(role.equals("admin")?"ADMIN":"USER");login=previous.getFirst().get("login_id").toString();jdbc.update("update users set password_hash=? where login_id=?",passwords.encode(password),login);}
        else{login="v2_"+role+"_"+UUID.randomUUID().toString().substring(0,8);auth.signup(new SignupRequest(login,password,role));}
        long id=jdbc.queryForObject("select id from users where login_id=?",Long.class,login);
        if(role.equals("admin"))jdbc.update("update users set role='ADMIN' where id=?",id);
        var credentials=Map.of("loginId",login,"password",password);accounts.put(role,credentials);
        var response=request(null,"POST","/api/auth/login",credentials);assertThat(response.status).isEqualTo(200);tokens.put(role,response.body.path("accessToken").asText());return id;
    }
    Map<String,Object> actor(String role,long uid,JsonNode chain,JsonNode keys)throws Exception{
        if(jdbc.queryForObject("select count(*) from faucet_grants where user_id=?",Integer.class,uid)==0)wallets.faucet(uid,UUID.randomUUID().toString());
        var prior=orders.findAll(uid).stream().filter(o->o.getStatus()==OrderStatus.FILLED&&o.getInputAmount().compareTo(new BigDecimal("75000"))==0).findFirst();
        PriceQuote quote;com.pricetrack.exchange.order.Order order;
        if(prior.isPresent()){order=prior.get();quote=quoteRows.findById(jdbc.queryForObject("select quote_id from price_quotes where order_id=? and user_id=?",String.class,order.getId(),uid)).orElseThrow();}
        else{quote=quotes.issue(uid,"mSEC",PriceReport.Side.BUY,new BigDecimal("75000"));order=orders.buy(uid,"mSEC",new BigDecimal("75000"),quote.getQuoteId());}
        await("V2_SPOT_NOT_SETTLED",35,()->{settlement.reconcilePendingTransactions();return orders.findOne(uid,order.getId()).getStatus()==OrderStatus.FILLED;});
        String suffix=role.equals("owner")?"A":"B",wallet=chain.path("user"+suffix).asText();
        if(walletLinks.find(uid).isEmpty()){
        var challenge=request(role,"POST","/api/c-futures/wallet/challenges",Map.of("wallet",wallet));assertThat(challenge.status).as("V2_WALLET_CHALLENGE_"+challenge.body.path("code").asText()).isEqualTo(200);
        var key=Credentials.create(keys.path("user"+suffix).asText());assertThat(key.getAddress()).isEqualToIgnoringCase(wallet);
        var sig=Sign.signPrefixedMessage(challenge.body.path("message").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8),key.getEcKeyPair());
        var proof=Map.of("challengeId",challenge.body.path("id").asText(),"signature",Numeric.toHexString(sig.getR())+Numeric.toHexString(sig.getS()).substring(2)+Numeric.toHexString(sig.getV()).substring(2));
        Instant linkStarted=Instant.now();
        var linked=request(role,"POST","/api/c-futures/wallet/link",proof);
        save("wallet-link-"+role+".json",Map.of("actor",role,"http",linked.status,"code",linked.body.path("code").asText(),"startedAt",linkStarted.toString(),"completedAt",Instant.now().toString(),"expiresAt",challenge.body.path("expiresAt").asText()));
        assertThat(linked.status).as("V2_WALLET_LINK_"+linked.body.path("code").asText()).isEqualTo(200);
        assertThat(request(role,"POST","/api/c-futures/wallet/link",proof).status).isEqualTo(409);
        }else assertThat(walletLinks.find(uid).orElseThrow().wallet()).isEqualToIgnoringCase(wallet);
        var priorPosition=ledger.positions(uid).stream().filter(p->p.leverage()==10&&Set.of("OPEN","LIQUIDATED").contains(p.status())).findFirst();
        String pid;
        if(priorPosition.isPresent())pid=priorPosition.get().id();
        else{var preview=commands.preview(uid,Action.OPEN,null,0,Side.LONG,10,BigInteger.TEN.pow(23));pid=commands.consume(uid,preview.id(),UUID.randomUUID().toString(),Action.OPEN,null,0).positionId();await("V2_LEGACY_OPEN",25,()->ledger.position(pid,uid).status().equals("OPEN"));}
        scenario.price("60000");
        awaitLiquidated(pid,uid);
        assertThat(futures.evidence(new ToolContext(uid,com.pricetrack.exchange.user.UserRole.USER,"test","READ_ONLY",null),pid).path("verification").asText()).isEqualTo("VERIFIED");
        scenario.price("75000");var un=commands.preview(uid,Action.OPEN,null,0,Side.LONG,2,BigInteger.TEN.pow(21));
        String unverified=commands.consume(uid,un.id(),UUID.randomUUID().toString(),Action.OPEN,null,0).positionId();
        await("V2_LEGACY_UNVERIFIED_OPEN",25,()->ledger.position(unverified,uid).status().equals("OPEN"));
        String pendingHash="0x"+UUID.randomUUID().toString().replace("-","").repeat(2);
        long pending=jdbc.queryForObject("insert into orders(user_id,symbol,side,order_type,input_amount,expected_output_amount,status,execution_mode,tx_hash,created_at,updated_at) values(?,'mSEC','BUY','MARKET',1000,0,'PENDING_ONCHAIN','ONCHAIN',?,now(),now()) returning id",Long.class,uid,pendingHash);
        jdbc.update("insert into blockchain_transactions(order_id,tx_hash,type,status,created_at,submitted_at) values(?,?,'BUY','SUBMITTED',now(),now())",pending,pendingHash);
        return Map.of("uid",uid,"quoteId",quote.getQuoteId(),"orderId",order.getId(),"pendingOrderId",pending,"positionId",pid,"unverifiedPositionId",unverified,"cWallet",wallet,"cPositionId",chain.path("position"+suffix).asText());
    }
    String fingerprint()throws Exception{
        var rows=new TreeMap<String,Object>();for(String table:List.of("orders","trades","price_quotes","blockchain_transactions","user_balances","futures_positions","futures_operations","futures_journal","futures_transactions"))rows.put(table,jdbc.queryForList("select * from "+table+" order by 1"));
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(rows)));
    }
    List<Map<String,Object>> budgetRows()throws Exception{
        var s=json.readTree(Path.of(System.getenv("AI_PILOT_V2_CONFIG")).toFile()).path("budget");
        try(var c=DriverManager.getConnection(s.path("jdbcUrl").asText(),s.path("dbUser").asText(),s.path("dbPassword").asText())){return PilotV2BudgetSnapshot.read(c,s.path("jdbcUrl").asText().split("/")[3].split("\\?")[0]);}
    }
    void process(List<String> args,int seconds,String log)throws Exception{
        var pb=new ProcessBuilder(args).directory(Path.of("..").toFile()).redirectErrorStream(true).redirectOutput(folder.resolve(log).toFile());
        var p=pb.start();if(!p.waitFor(seconds,TimeUnit.SECONDS)){p.destroyForcibly();throw new IllegalStateException("V2_OWN_CHILD_TIMEOUT");}assertThat(p.exitValue()).as(log).isZero();
    }
    @Test void completeOwnedFixtureFrozenScheduleAndRealSixDomSubmissions()throws Exception{
        String scope=System.getenv().getOrDefault("AI_PILOT_V2_HTTP_SCOPE","FULL");assertThat(scope).isIn("FULL","A2_PREFIX","NOVEL30","C_LATENCY","RECEIPT_AGENT");boolean a2Only=scope.equals("A2_PREFIX");
        folder=Path.of(System.getenv("AI_PILOT_V2_FOLDER"));session=folder.resolve("session");origin="http://127.0.0.1:"+port;password=UUID.randomUUID().toString();
        assertThat(request(null,"GET","/api/me",null).status).isEqualTo(401);ledger.initializeEmptyFixture();walletLinks.installSchema();
        var chain=json.readTree(folder.resolve("fixture/test-chain.json").toFile());var keys=json.readTree(folder.resolve("keys/wallet-keys.private.json").toFile());
        long owner=signup("owner"),other=signup("other"),admin=signup("admin");
        actors.put("owner",actor("owner",owner,chain,keys));actors.put("other",actor("other",other,chain,keys));actors.put("admin",Map.of("uid",admin));
        worker.close(); // Stops signal() and tick scheduling, not only HTTP writes. No legacy signing work remains.
        var executor=(ExecutorService)org.springframework.test.util.ReflectionTestUtils.getField(worker,"executor");assertThat(executor.awaitTermination(15,TimeUnit.SECONDS)).isTrue();assertThat(executor.isTerminated()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from futures_positions where pending_operation is not null",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from futures_transactions where status in ('PREPARED','SIGNED','SUBMITTED')",Integer.class)).isZero();
        var identity=json.readTree(folder.resolve("fixture/c-identity.json").toFile());
        var cid=Map.of("deploymentId",request("owner","GET","/api/c-futures/config",null).body.at("/value/deploymentId").asText(),"chainId","31337","genesisHash",identity.path("genesisHash").asText(),"token",identity.at("/token/address").asText(),"controller",identity.at("/controller/address").asText(),"vault",identity.at("/vault/address").asText());
        String before=fingerprint(),cBefore=cFingerprint();frozenDb=before;frozenC=cBefore;save("c-before-fingerprint.json",Map.of("hash",cBefore));save("bind-descriptor.private.json",Map.of("origin",origin,"accounts",accounts,"recipe",Map.of("scope","NEW_ISOLATED_PILOT_V2_NOT_USER_DATA","mode","REAL_ISOLATED","financiallyFrozen",true,"financialFingerprint",before,"actors",actors,"cIdentity",cid)));
        Files.createDirectory(session);String python=System.getenv().getOrDefault("AI_EVAL_TEST_PYTHON","python");
        process(List.of(python,"-B","-S","deployment/ai_pilot_bind_live.py",folder.resolve("bind-descriptor.private.json").toString(),session.toString()),45,"bind.private.log");
        assertThat(request("admin","POST","/api/ai/provider-budget/initialize",Map.of()).status).isEqualTo(200);
        var channel=channels.getObject();var rows=json.readTree(session.resolve("rows.private.json").toFile());var results=new ArrayList<Object>();
        for(String phase:List.of("INDEX_LEGACY","SAFETY_LEGACY","NOVEL30","INDEX_PILOT","SAFETY_C","LATENCY")){
            if(phase.startsWith("INDEX_")){
                pace("admin");var claimed=channel.control(Map.of("op","claim","phase",phase));var r=request("admin","POST","/api/ai/index",Map.of());assertThat(r.status).as(phase).isEqualTo(200);
                assertThat(r.body.path("chunks").asInt()).isEqualTo(phase.equals("INDEX_LEGACY")?88:93);
                assertThat(r.body.path("documents").asInt()).isEqualTo(phase.equals("INDEX_LEGACY")?9:10);assertThat(r.body.path("unchanged").asBoolean()).isFalse();
                channel.control(Map.of("op","complete","ticket",claimed.path("ticket").asText(),"serverRequest",channel.control(Map.of("op","index-request")).path("serverRequest").asText()));
            }else for(var row:rows)if(row.path("phase").asText().equals(phase)){
                if(scope.equals("RECEIPT_AGENT")&&phase.equals("SAFETY_LEGACY")&&row.path("case").asText().equals("PF1b")){
                    // Observed old gap ~178s; diagnostic idle is outside every product request deadline.
                    save("receipt-agent-idle.json",Map.of("before",Instant.now().toString(),"monotonicNs",System.nanoTime(),"plannedMs",180000));Thread.sleep(180000);
                }
                if(ReceiptProbeTrace.ENABLED)ReceiptProbeTrace.current.set(results.size()+1);
                JsonNode a2History=a2Only&&row.path("case").asText().equals("A2")?a2History(results):json.nullNode();
                pace(row.path("actor").asText(),row.path("case").asText());var claimed=channel.control(Map.of("op","claim","phase",phase,"slot",row.path("slot").asInt()));
                long httpStart=System.nanoTime();Instant httpUtc=Instant.now();var r=request(row.path("actor").asText(),"POST","/api/ai/agent/answers",row.path("body"));
                results.add(Map.of("case",row.path("case").asText(),"phase",phase,"http",r.status,"reply",r.body,"httpElapsedMs",(System.nanoTime()-httpStart)/1_000_000,"requestStartedAt",httpUtc.toString(),"monotonicStartNs",httpStart));
                Files.writeString(folder.resolve("http.private.json"),json.writeValueAsString(results));
                assertThat(r.status).as(row.path("case").asText()+":"+r.body.path("error").asText()).isEqualTo(row.path("expectedHttp").asInt());
                verifyReply(row,r.body);
                if(phase.equals("SAFETY_C"))save("c-check-"+row.path("case").asText()+".json",PilotV2CTailChecks.verify(row.path("case").asText(),r.body,cid.get("deploymentId")));
                channel.control(Map.of("op","complete","ticket",claimed.path("ticket").asText(),"serverRequest",r.body.path("runId").asText()));
                if(a2Only&&row.path("case").asText().equals("A2")){finishA2(row,r,results,a2History,before,channel);return;}
            }
            assertThat(channel.control(Map.of("op","reconcile","pgRows",budgetRows())).path("matching").asBoolean()).isTrue();
            if(Set.of("SAFETY_C","LATENCY").contains(phase)){
                assertThat(fingerprint()).isEqualTo(before);assertThat(cFingerprint()).isEqualTo(cBefore);
                save("phase-"+phase+".json",Map.of("financialUnchanged",true,"cStateUnchanged",true,"budgetMatching",true,"phase",phase));
            }
            channel.control(Map.of("op","advance","evidenceReviewed",true));
            if(scope.equals("RECEIPT_AGENT")&&phase.equals("SAFETY_LEGACY")){
                assertThat(results).hasSize(6);assertThat(fingerprint()).isEqualTo(before);assertThat(cFingerprint()).isEqualTo(cBefore);
                save("receipt-agent-result.json",Map.of("requests",6,"financialUnchanged",true,"cStateUnchanged",true,"budgetMatching",true,"status","PASS_PREFIX_NOT_FULL_ACCEPTANCE"));return;
            }
            if(scope.equals("NOVEL30")&&phase.equals("NOVEL30")){
                assertThat(results).hasSize(36);assertThat(fingerprint()).isEqualTo(before);
                save("http-result-novel30.json",Map.of("scope","NOVEL30","httpRequests",36,"novelQuestions",30,"controlledProvider",true,"realModel",false,"financialUnchanged",true,"budgetMatching",true,"quality","NOT_RUN_REAL_MODEL"));return;
            }
        }
        if(scope.equals("C_LATENCY")){
            assertThat(results).hasSize(51);assertThat(fingerprint()).isEqualTo(before);assertThat(cFingerprint()).isEqualTo(cBefore);
            save("http-result-c-latency.json",Map.of("scope","C_LATENCY","httpRequests",51,"cRequests",3,"latencyRequests",12,"financialUnchanged",true,"cStateUnchanged",true,"budgetMatching",true,"browser","NOT_RUN","realModelQuality","NOT_RUN"));return;
        }
        // Browser private host: actual DOM HTTP supplies reply; parent never supplies an Agent answer.
        var browserRows=new ArrayList<JsonNode>();for(var r:rows)if(r.path("phase").asText().equals("WEB_PILOT"))browserRows.add(r);
        save("browser.private.json",Map.of("origin",origin,"mode","UNPAID_CONTROLLED_ONLY","accounts",Map.of("owner",accounts.get("owner"),"other",accounts.get("other")),"rows",browserRows));
        var browserCommand="true".equals(System.getenv("AI_PILOT_NATIVE_BROWSER"))?List.of(python,"-B","-S","deployment/ai_pilot_native_bridge.py","child",folder.toString()):List.of("node","tools/websocket-test-client/scripts/ai-pilot-v2-browser.mjs",folder.resolve("browser.private.json").toString(),folder.resolve("browser").toString());
        var pb=new ProcessBuilder(browserCommand).directory(Path.of("..").toFile()).redirectError(folder.resolve("browser.private.log").toFile());
        var browser=pb.start();String ticket=null;int submitted=0;
        try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(browser.getInputStream()));var writer=new java.io.PrintWriter(browser.getOutputStream(),true)){
            String line;while((line=reader.readLine())!=null){var f=json.readTree(line);if(f.path("op").asText().equals("before")){var row=browserRows.get(f.path("slot").asInt());pace(row.path("actor").asText());assertThat(f.path("body")).isEqualTo(row.path("body"));ticket=channel.control(Map.of("op","claim","phase","WEB_PILOT","slot",f.path("slot").asInt())).path("ticket").asText();}
                else if(f.path("op").asText().equals("after")){assertThat(f.at("/reply/http").asInt()).isEqualTo(200);verifyReply(browserRows.get(f.path("slot").asInt()),f.at("/reply/body"));channel.control(Map.of("op","complete","ticket",ticket,"serverRequest",f.at("/reply/body/runId").asText()));submitted++;}
                else throw new IllegalStateException("V2_BROWSER_STOP");writer.println("{\"ok\":true}");}
        }finally{if(!browser.waitFor(10,TimeUnit.SECONDS))browser.destroyForcibly();}
        assertThat(browser.exitValue()).isZero();assertThat(submitted).isEqualTo(6);
        assertThat(fingerprint()).isEqualTo(before);assertThat(channel.control(Map.of("op","reconcile","pgRows",budgetRows())).path("matching").asBoolean()).isTrue();
        channel.control(Map.of("op","advance","evidenceReviewed",true));
        save("http-result.json",Map.of("controlledProvider",true,"realModel",false,"httpRequests",results.size(),"browserRequests",6,"financialUnchanged",true,"paidCalls",0,"keyReads",0));
    }
    void pace(String actor)throws Exception{
        pace(actor,"INDEX_OR_BROWSER");
    }
    @AfterEach void finalFrozenState()throws Throwable{
        ReceiptProbeTrace.finalizeEvidence(()->{if(ReceiptProbeTrace.ENABLED&&folder!=null)ReceiptProbeTrace.flush(json,folder);},this::verifyFinalFrozenState);
    }
    void verifyFinalFrozenState()throws Throwable{
        if(frozenDb==null)return;
        String db,chain;
        try{db=fingerprint();chain=cFingerprint();}
        catch(Throwable e){save("final-fingerprint.json",Map.of("observation","UNAVAILABLE","financialUnchanged","UNKNOWN","cStateUnchanged","UNKNOWN"));throw e;}
        save("final-fingerprint.json",Map.of("observation","COMPLETE","dbBefore",frozenDb,"dbAfter",db,"cBefore",frozenC,"cAfter",chain,"financialUnchanged",frozenDb.equals(db),"cStateUnchanged",frozenC.equals(chain)));
        assertThat(db).isEqualTo(frozenDb);assertThat(chain).isEqualTo(frozenC);
    }
    String cFingerprint()throws Exception{
        var values=new TreeMap<String,JsonNode>();
        for(String actor:List.of("owner","other")){
            var account=request(actor,"GET","/api/c-futures/account",null);assertThat(account.status).isEqualTo(200);
            String pid=((Map<?,?>)actors.get(actor)).get("cPositionId").toString();var position=request(actor,"GET","/api/c-futures/positions/"+pid,null);assertThat(position.status).isEqualTo(200);
            assertThat(account.body.path("value").isObject()).isTrue();assertThat(position.body.path("value").isObject()).isTrue();
            values.put(actor+"-account",account.body.path("value"));values.put(actor+"-position",position.body.path("value"));
        }
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(values)));
    }
    void pace(String actor,String caseId)throws Exception{
        var settings=json.readTree(Path.of(System.getenv("AI_PILOT_V2_CONFIG")).toFile()).path("budget");
        try(var c=DriverManager.getConnection(settings.path("jdbcUrl").asText(),settings.path("dbUser").asText(),settings.path("dbPassword").asText())){
            long uid=((Number)((Map<?,?>)actors.get(actor)).get("uid")).longValue();
            PilotV2Pacing.await(()->PilotV2Pacing.read(c,uid),Clock.systemUTC(),System::nanoTime,Thread::sleep,
                event->Files.writeString(folder.resolve("pacing.private.jsonl"),json.writeValueAsString(Map.of("actor",actor,"case",caseId,"timing",event))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND));
        } // evaluation pacing is excluded from HTTP latency; server guard unchanged
    }
    java.sql.Connection budgetConnection()throws Exception{
        var s=json.readTree(Path.of(System.getenv("AI_PILOT_V2_CONFIG")).toFile()).path("budget");return DriverManager.getConnection(s.path("jdbcUrl").asText(),s.path("dbUser").asText(),s.path("dbPassword").asText());
    }
    JsonNode a2History(List<Object> results)throws Exception{
        var expected=new HashSet<String>();var rows=new ArrayList<Object>();
        for(Object result:results){var m=(Map<?,?>)result;if(Set.of("PF1a","PF1b","PA2a","PA2b","A1").contains(m.get("case")))expected.add(((JsonNode)m.get("reply")).path("runId").asText());}
        long owner=((Number)((Map<?,?>)actors.get("owner")).get("uid")).longValue();Instant now=Instant.now();
        try(var c=budgetConnection();var q=c.prepareStatement("select request_id::text,created_at from ai_provider_budget.request where actor=? order by created_at")){
            q.setQueryTimeout(5);q.setLong(1,owner);try(var r=q.executeQuery()){while(r.next())rows.add(Map.of("requestId",r.getString(1),"createdAt",r.getTimestamp(2).toInstant().toString()));}
        }
        var value=json.valueToTree(Map.of("actor",owner,"at",now.toString(),"monotonicNs",System.nanoTime(),"requests",rows));save("a2-before-pacing.json",value);
        assertThat(expected).hasSize(5);assertThat(rows).hasSize(5);var actual=new HashSet<String>();
        for(var r:value.path("requests")){actual.add(r.path("requestId").asText());assertThat(Instant.parse(r.path("createdAt").asText())).isAfterOrEqualTo(now.minusSeconds(60));}
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);return value;
    }
    void finishA2(JsonNode row,Reply response,List<Object> results,JsonNode history,String before,PilotV2Channel channel)throws Exception{
        var frozen=json.readTree(Path.of("src/test/resources/ai/novel-30-v2.json").toFile());JsonNode expected=null;for(var c:frozen.path("cases"))if(c.path("id").asText().equals("A2"))expected=c;
        assertThat(expected).isNotNull();assertThat(row.at("/body/question")).isEqualTo(expected.path("question"));assertThat(response.body.path("route")).isEqualTo(expected.path("route"));
        var market=new ArrayList<JsonNode>();for(var t:response.body.path("toolEvidence"))if(Set.of("getMarketStatus","getCurrentReferencePrice").contains(t.path("tool").asText()))market.add(t);
        assertThat(market).isNotEmpty().allMatch(t->t.path("status").asText().equals("SUCCESS"));assertThat(market.getFirst().at("/data/provider").asText()).isEqualTo("SIMULATED");
        assertThat(market.getFirst().at("/data/volumeMeaning").asText()).isEqualTo("GENERATED_NOT_USER_FILLS");
        var reservations=new ArrayList<Object>();try(var c=budgetConnection();var q=c.prepareStatement("select kind,state from ai_provider_budget.reservation where request_id=?::uuid order by kind")){
            q.setQueryTimeout(5);q.setString(1,response.body.path("runId").asText());try(var r=q.executeQuery()){while(r.next())reservations.add(Map.of("kind",r.getString(1),"state",r.getString(2)));}
        }
        var reserved=json.valueToTree(reservations);int embeds=0,llm=0;for(var r:reserved){assertThat(r.path("state").asText()).isEqualTo("OBSERVED");if(r.path("kind").asText().equals("EMBEDDING"))embeds++;else if(r.path("kind").asText().equals("LLM"))llm++;}
        assertThat(embeds).isEqualTo(1);assertThat(llm).isEqualTo(1);assertThat(response.body.at("/metrics/retrievalCalls").asInt()).isEqualTo(1);assertThat(response.body.path("knowledgeSources")).isNotEmpty();
        var support=new LinkedHashMap<String,Boolean>();for(var atom:expected.path("atoms")){var a=frozen.path("atoms").path(atom.asText());boolean found=false;for(var hit:response.body.path("knowledgeSources"))if(hit.path("path").equals(a.path("path"))&&hit.path("content").asText().contains(a.path("needle").asText()))found=true;support.put(atom.asText(),found);}
        boolean matching=channel.control(Map.of("op","reconcile","pgRows",budgetRows())).path("matching").asBoolean();assertThat(matching).isTrue();assertThat(fingerprint()).isEqualTo(before);
        var output=new LinkedHashMap<String,Object>();output.put("scope","A2_PREFIX");output.put("httpRequests",results.size());assertThat(results).hasSize(8);
        output.put("a2TransportPass",true);output.put("financialUnchanged",true);output.put("budgetMatching",matching);output.put("controlledProvider",true);output.put("realModel",false);output.put("full30","NOT_RUN");output.put("realModelQuality","NOT_RUN_CONTROLLED_MODEL");
        output.put("assessment",Map.of("route",response.body.path("route").asText(),"responseStatus",response.body.path("status").asText(),"frozenAtomSupport",support,"finalAnswerQuality","NOT_RUN_CONTROLLED_MODEL","reservations",reservations,"history",history));
        save("http-result-a2.json",output);
    }
    void verifyReply(JsonNode row,JsonNode reply){
        String id=row.path("case").asText();
        if(id.equals("D5")){assertThat(reply.at("/metrics/modelCalls").asInt()).isZero();assertThat(reply.at("/metrics/retrievalCalls").asInt()).isZero();return;}
        assertThat(reply.path("error").isNull()).as(id).isTrue();
        for(var u:reply.path("uncertainties"))assertThat(u.asText()).as(id).isNotIn("PLAN_UNAVAILABLE","SYNTHESIS_UNAVAILABLE","RAG_UNAVAILABLE","KNOWLEDGE_APPROVAL_UNVERIFIED","AGENT_CONTEXT_LIMIT","AI_ACCOUNT_RATE_LIMIT","AI_BUDGET_EXHAUSTED","AI_BUDGET_UNAVAILABLE");
        var successful=new HashSet<String>();
        for(var t:reply.path("toolEvidence")){assertThat(t.path("status").asText()).as(id+":"+t.path("tool").asText()).isEqualTo("SUCCESS");successful.add(t.path("tool").asText());}
        String required=switch(id){case "PF1a"->"getOrder";case "PF1b"->"getQuote";case "PA2a","PA2b"->"getCurrentReferencePrice";case "PC4a","PC4b"->"listAbnormalOrders";case "PCa","PCc"->"getCAccount";case "PCb"->"getCPosition";default->null;};
        if(required!=null)assertThat(successful).as(id).contains(required);
        if(reply.at("/metrics/retrievalCalls").asInt()>0){assertThat(reply.path("indexVersion").asText()).as(id).isNotBlank();assertThat(reply.path("knowledgeSources")).as(id).isNotEmpty();}
        for(var source:reply.path("knowledgeSources")){
            if(!row.path("actor").asText().equals("admin"))assertThat(source.path("role").asText()).as(id).isEqualTo("USER");
            if(Set.of("PCa","PCb","PCc").contains(id))assertThat(source.path("path").asText()).isEqualTo("c-vault-read-policy.md");
            else assertThat(source.path("path").asText()).isNotEqualTo("c-vault-read-policy.md");
        }
        assertThat(reply.path("answer").asText()).as(id).isNotBlank();
    }
}
