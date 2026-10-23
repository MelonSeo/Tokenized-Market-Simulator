import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pricetrack.exchange.auth.JwtTokenProvider;
import com.pricetrack.exchange.custody.abi.CAbiModels.*;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.*;
import com.pricetrack.exchange.custody.auth.CWalletLinkStore;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.web3j.crypto.*;

/** Trial-key Driver only. Never placed on the Spring server classpath or mounted into its filesystem. */
public final class CCustodyLiveDriver {
    private static final String ORIGIN="http://localhost:5173";
    private final Path fixture,privateDir,configDir,serverEvidence;
    private final Properties manifest=new Properties();
    private final CAnvilContractGate g;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER)
        .proxy(new ProxySelector(){public List<Proxy> select(URI u){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,IOException e){}}).build();
    private CCustodyIdentity identity;
    private String ownerPassword,apiPassword,jwtSecret,run,source,database,dbOwner,apiUser,marker;
    private String positionA,positionB,operationClose;
    private String stage="INIT";
    private long sequence;
    private record Api(int status,JsonNode body) {}
    public static void main(String[] args){
        if(args.length==1&&args[0].equals("--self-test")){try{CAnvilContractGate.verifyTransportPolicy();selfTest();System.out.println("C_LIVE_DRIVER_OFFLINE_SELF_TEST_PASS");return;}catch(Exception invalid){throw new IllegalStateException("C_LIVE_DRIVER_SELF_TEST");}}
        if(args.length!=4||!Arrays.equals(args,new String[]{"/fixture","/driver-private","/server-config","/server-evidence"}))throw new IllegalArgumentException("C_LIVE_DRIVER_ARGS");
        CCustodyLiveDriver driver=null;
        try{CAnvilContractGate.verifyTransportPolicy();driver=new CCustodyLiveDriver(args);driver.run();System.out.println("C_LIVE_DRIVER_PASS");}
        catch(Exception error){
            if(driver!=null)try{ObjectNode failure=CAnvilContractGate.JSON.createObjectNode().put("stage",driver.stage).put("errorType",error.getClass().getSimpleName()).put("code",safeCode(error)).put("resend",false);if(error instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[A-Z0-9]{5}"))failure.put("sqlState",sql.getSQLState());driver.g.save("failure.json",failure);}catch(Exception ignored){}
            System.err.println("C_LIVE_DRIVER_STOP_NO_RETRY");System.exit(1);
        }
    }
    CCustodyLiveDriver(String[] args)throws Exception{
        fixture=Path.of(args[0]);privateDir=Path.of(args[1]);configDir=Path.of(args[2]);serverEvidence=Path.of(args[3]);
        try(var in=Files.newInputStream(fixture.resolve("manifest.properties"))){manifest.load(in);}
        run=manifest.getProperty("runId");source=manifest.getProperty("sourceIdentity");database=manifest.getProperty("database");dbOwner=manifest.getProperty("databaseOwner");apiUser=manifest.getProperty("apiUser");marker=manifest.getProperty("marker");
        check(run!=null&&run.matches("c-live-[0-9a-f]{16}")&&source!=null&&source.matches("[0-9a-f]{64}")&&marker!=null&&marker.matches("[0-9a-f]{64}"),"MANIFEST");
        for(String name:List.of(database,dbOwner,apiUser))check(name!=null&&name.matches("[a-z][a-z0-9_]{1,60}"),"DATABASE_NAMES");
        check(!dbOwner.equals(apiUser),"DATABASE_ROLE_SEPARATION");ownerPassword=secret("C_GATE_OWNER_PASSWORD");apiPassword=secret("C_GATE_API_PASSWORD");jwtSecret=secret("C_GATE_JWT_SECRET");
        check(!ownerPassword.equals(apiPassword)&&!jwtSecret.equals(ownerPassword)&&!jwtSecret.equals(apiPassword),"SECRET_SEPARATION");
        g=new CAnvilContractGate(fixture,privateDir);
    }
    private void run()throws Exception{
        stage="SOURCE_AND_WRITE_PROBES";g.verifySourceMetadata();g.save("driver-write-probe.json",CAnvilContractGate.JSON.createObjectNode().put("runId",run));
        stage="REAL_HTTP_TRANSPORT_FAILURES";transportFailures();
        stage="POSTGRES_OWNERSHIP_SCHEMA_SCRAM";databaseBootstrap();
        stage="NEW_CHAIN_BOOTSTRAP";chainBootstrap();
        stage="SERVER_CONFIG";writeServerProperties();g.save("bootstrap-ready.json",CAnvilContractGate.JSON.createObjectNode().put("runId",run).put("sourceIdentity",source).put("result","READY_FOR_SELECTED_SPRING"));
        stage="WAIT_SELECTED_SPRING";awaitServer();
        stage="ACTUAL_HTTP_JWT_WALLET_READS";apiAcceptance();
        stage="FINAL_DATABASE_PRESERVATION";databaseFinal();
        g.save("live-result.json",CAnvilContractGate.JSON.createObjectNode().put("runId",run).put("sourceIdentity",source).put("result","PASS")
            .put("scope","C_READ_WALLET_ACTUAL_HTTP_PG_ANVIL_NOT_FULL_TMS_OR_BROWSER").put("actualJwtFilterAndJpa",true)
            .put("actualWalletProof",true).put("actualPinnedReads",true).put("actualPgLinkRaceAndRollback",true).put("actualHttpFaultWireCount",true)
            .put("completeCoverage",false).put("realMetaMask",false).put("productSynthetic",false).put("transactionSenderRun",false));
    }
    private void transportFailures()throws Exception{
        Map<String,AtomicInteger> counts=new ConcurrentHashMap<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",18091),0);ExecutorService threads=Executors.newFixedThreadPool(2);
        server.setExecutor(threads);server.createContext("/",exchange->{String path=exchange.getRequestURI().getPath();counts.computeIfAbsent(path,p->new AtomicInteger()).incrementAndGet();
            try{
                exchange.getRequestBody().readNBytes(16_385);
                if(path.equals("/503")){exchange.getResponseHeaders().add("Retry-After","0");exchange.sendResponseHeaders(503,-1);}
                else if(path.equals("/redirect")){exchange.getResponseHeaders().add("Location","http://127.0.0.1:18091/redirect-target");exchange.sendResponseHeaders(302,-1);}
                else if(path.equals("/auth")){exchange.getResponseHeaders().add("WWW-Authenticate","Basic realm=fixture");exchange.sendResponseHeaders(401,-1);}
                else if(path.equals("/slow")){exchange.sendResponseHeaders(200,0);var out=exchange.getResponseBody();out.write('{');out.flush();Thread.sleep(800);out.write("\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x1\"}".getBytes(StandardCharsets.UTF_8));}
                else{exchange.sendResponseHeaders(500,-1);}
            }catch(Exception expectedCancelledBody){}finally{exchange.close();}
        });server.start();
        try{
            ObjectNode evidence=CAnvilContractGate.JSON.createObjectNode();
            for(String path:List.of("/503","/redirect","/auth","/slow")){
                stage="REAL_HTTP_"+path.substring(1).toUpperCase(Locale.ROOT);long started=System.nanoTime();String code="C_LIVE_UNEXPECTED_HTTP_SUCCESS";
                try(CCustodyRpcClient client=new CCustodyRpcClient(URI.create("http://127.0.0.1:18091"+path))){
                    try{client.request("eth_chainId",List.of(),path.equals("/slow")?200:3000);}catch(CCustodyFailure safe){code=safe.code();}
                }
                long elapsed=(System.nanoTime()-started)/1_000_000;int wire=counts.getOrDefault(path,new AtomicInteger()).get();
                // Persist the exact safe observation BEFORE expectations so the first failure remains diagnosable.
                g.save("http-observed-"+path.substring(1)+".json",CAnvilContractGate.JSON.createObjectNode().put("case",path.substring(1)).put("wireRequests",wire).put("code",code).put("elapsedMs",elapsed));
                check(wire==1,"HTTP_WIRE_COUNT");
                check(path.equals("/slow")?code.equals("C_RPC_TIMEOUT")&&elapsed>=100&&elapsed<3000:code.equals("C_RPC_HTTP_STATUS"),"HTTP_FAULT_BOUNDARY");
                evidence.set(path.substring(1),CAnvilContractGate.JSON.createObjectNode().put("wireRequests",1).put("code",code).put("elapsedMs",elapsed));
            }
            check(counts.getOrDefault("/redirect-target",new AtomicInteger()).get()==0,"HTTP_REDIRECT_NOT_FOLLOWED");g.save("actual-http-faults.json",evidence);
        }finally{server.stop(0);threads.shutdownNow();check(threads.awaitTermination(3,TimeUnit.SECONDS),"HTTP_FIXTURE_STOP");}
    }
    private Connection ownerConnection()throws SQLException{return DriverManager.getConnection(jdbc("c_live_driver"),dbOwner,ownerPassword);}
    private Connection apiConnection()throws SQLException{return DriverManager.getConnection(jdbc("c_live_api_probe"),apiUser,apiPassword);}
    private String jdbc(String app){return "jdbc:postgresql://pg:5432/"+database+"?sslmode=disable&connectTimeout=3&socketTimeout=4&ApplicationName="+app;}
    private void databaseBootstrap()throws Exception{
        try(Connection c=ownerConnection();Statement s=c.createStatement()){
            s.setQueryTimeout(3);
            try(ResultSet r=s.executeQuery("SELECT current_database(),current_user")){check(r.next()&&r.getString(1).equals(database)&&r.getString(2).equals(dbOwner),"OWNER_DATABASE");}
            // A just-created database must be empty. Never accept a pre-existing fixture marker/schema.
            try(ResultSet r=s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")){check(r.next()&&r.getLong(1)==0,"NEW_DATABASE_EMPTY");}
            // Session-local only: never let a failed CREATE ROLE duplicate its password into PostgreSQL logs.
            s.execute("SET log_statement='none'");s.execute("SET log_min_error_statement='PANIC'");s.execute("SET log_parameter_max_length_on_error=0");
            s.execute("CREATE TABLE public.c_live_fixture_identity(run_id text NOT NULL,marker text NOT NULL)");
            try(PreparedStatement p=c.prepareStatement("INSERT INTO public.c_live_fixture_identity VALUES(?,?)")){p.setString(1,run);p.setString(2,marker);p.executeUpdate();}
            s.execute("CREATE TABLE public.c_live_preservation_sentinel(value text PRIMARY KEY)");s.execute("INSERT INTO public.c_live_preservation_sentinel VALUES('PRESERVE_C_LIVE_SOURCE_AND_USERS')");
            s.execute("CREATE TABLE public.users(id BIGSERIAL PRIMARY KEY,login_id VARCHAR(50) UNIQUE,email VARCHAR(255) UNIQUE,password_hash VARCHAR(255),email_verified BOOLEAN NOT NULL DEFAULT FALSE,google_sub VARCHAR(255) UNIQUE,nickname VARCHAR(100) NOT NULL,role VARCHAR(20) NOT NULL,wallet_address VARCHAR(255),created_at TIMESTAMP NOT NULL)");
            s.execute("INSERT INTO public.users(id,login_id,password_hash,nickname,role,created_at) VALUES (1,'c-live-user-1','not-used-for-password-login','C user1','USER',CURRENT_TIMESTAMP),(2,'c-live-user-2','not-used-for-password-login','C user2','USER',CURRENT_TIMESTAMP),(3,'c-live-admin','not-used-for-password-login','C admin','ADMIN',CURRENT_TIMESTAMP),(4,'c-live-rollback','not-used-for-password-login','C rollback','USER',CURRENT_TIMESTAMP)");
            byte[] schema=Files.readAllBytes(fixture.resolve("c-wallet-schema.sql"));check(schema.length>0&&schema.length<=16_384,"WALLET_SCHEMA_CAP");s.execute(new String(schema,StandardCharsets.UTF_8));
            s.execute("CREATE ROLE "+apiUser+" LOGIN PASSWORD '"+apiPassword+"'");
            s.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");s.execute("GRANT CONNECT ON DATABASE "+database+" TO "+apiUser);
            s.execute("GRANT USAGE ON SCHEMA public,tms_c_identity TO "+apiUser);
            s.execute("GRANT SELECT,UPDATE(id) ON public.users TO "+apiUser);
            s.execute("GRANT SELECT ON public.c_live_fixture_identity TO "+apiUser);
            s.execute("GRANT SELECT,INSERT ON tms_c_identity.wallet_links,tms_c_identity.wallet_challenges TO "+apiUser);
            s.execute("GRANT UPDATE(consumed,proof_signature) ON tms_c_identity.wallet_challenges TO "+apiUser);
            s.execute("CREATE FUNCTION public.c_live_reject_user_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='C_LIVE_USERS_READ_ONLY'; END $$");
            s.execute("CREATE TRIGGER c_live_users_readonly BEFORE UPDATE OR DELETE OR TRUNCATE ON public.users FOR EACH STATEMENT EXECUTE FUNCTION public.c_live_reject_user_change()");
            try(PreparedStatement p=c.prepareStatement("SELECT rolpassword FROM pg_authid WHERE rolname=?")){p.setString(1,apiUser);try(ResultSet r=p.executeQuery()){check(r.next()&&r.getString(1).startsWith("SCRAM-SHA-256$"),"SCRAM_FORMAT");}}
        }
        try(Connection c=apiConnection();Statement s=c.createStatement()){
            s.setQueryTimeout(3);try(ResultSet r=s.executeQuery("SELECT current_user")){check(r.next()&&r.getString(1).equals(apiUser),"API_ROLE_LOGIN");}
            try(ResultSet r=s.executeQuery("SELECT current_setting('log_parameter_max_length_on_error'),current_setting('log_statement')")){check(r.next()&&r.getString(1).equals("0")&&r.getString(2).equals("none"),"API_PARAMETER_LOGGING_DISABLED");}
            c.setAutoCommit(false);try(ResultSet r=s.executeQuery("SELECT id FROM public.users WHERE id=1 FOR UPDATE")){check(r.next(),"API_ROW_LOCK_PERMISSION");}c.rollback();c.setAutoCommit(true);
            try{s.executeUpdate("UPDATE public.users SET id=id WHERE id=1");throw new IllegalStateException("C_LIVE_USER_MUTATION_ALLOWED");}catch(SQLException rejected){check("55000".equals(rejected.getSQLState()),"USER_MUTATION_TRIGGER");}
            try{s.execute("CREATE TABLE public.c_not_allowed(id int)");throw new IllegalStateException("C_LIVE_API_DDL_ALLOWED");}catch(SQLException rejected){check("42501".equals(rejected.getSQLState()),"API_DDL_DENIED");}
        }
        try(Connection unexpected=DriverManager.getConnection(jdbc("c_live_bad_password"),apiUser,"wrong-"+apiPassword)){throw new IllegalStateException("C_LIVE_SCRAM_ACCEPTED_BAD_PASSWORD");}
        catch(SQLException rejected){check("28P01".equals(rejected.getSQLState()),"SCRAM_AUTHENTICATION");}
        g.save("database-bootstrap.json",CAnvilContractGate.JSON.createObjectNode().put("database",database).put("owner",dbOwner).put("apiUser",apiUser).put("scramVerified",true).put("apiUserMutationDenied",true).put("apiDdlDenied",true).put("financialTablesCreated",0));
    }
    private void chainBootstrap()throws Exception{
        for(String role:List.of("treasury","admin","relayer","oracle","userA","userB","proofC"))g.keys.put(role,Keys.createEcKeyPair(new SecureRandom()));
        ObjectNode keys=CAnvilContractGate.JSON.createObjectNode(),addresses=CAnvilContractGate.JSON.createObjectNode();for(var e:g.keys.entrySet()){keys.put(e.getKey(),e.getValue().getPrivateKey().toString(16));addresses.put(e.getKey(),CAnvilContractGate.address(e.getValue()));}
        check(new HashSet<>(g.keys.values().stream().map(CAnvilContractGate::address).toList()).size()==7,"ROLE_KEYS");g.save("keys.private.json",keys);g.save("addresses.json",addresses);
        check(CAnvilContractGate.number(g.rpc("eth_chainId")).equals(CAnvilContractGate.CHAIN),"CHAIN_ID");g.genesis=g.rpc("eth_getBlockByNumber","0x0",false).path("hash").asText();
        for(String role:List.of("treasury","admin","relayer","oracle","userA","userB"))g.rpc("anvil_setBalance",g.addr(role),CAnvilContractGate.hex(CAnvilContractGate.units(100)));
        g.symbol=CAnvilContractGate.hash("C_READ_WALLET_TRIAL_SYMBOL");g.policy=CAnvilContractGate.hash("C_READ_WALLET_TRIAL_POLICY");
        g.token=g.deploy("CMockCollateral",CAnvilContractGate.words(CAnvilContractGate.addressWord(g.addr("treasury"))),g.keys.get("treasury"));
        g.controller=g.deploy("CCollateralizedFuturesController",CAnvilContractGate.words(CAnvilContractGate.addressWord(g.token),CAnvilContractGate.addressWord(g.addr("relayer")),CAnvilContractGate.addressWord(g.addr("oracle")),CAnvilContractGate.addressWord(g.addr("admin")),g.symbol,g.policy),g.keys.get("treasury"));
        g.rules=g.callAddress(g.controller,"rules()");g.vault=g.callAddress(g.controller,"vault()");
        g.verifyRuntime("CMockCollateral",g.token);g.verifyRuntime("CCollateralizedFuturesController",g.controller);g.verifyRuntime("FuturesEngine",g.rules);g.verifyRuntime("CMarginVault",g.vault);
        identity=new CCustodyIdentity(CAnvilContractGate.CHAIN,g.genesis,binding(g.token),binding(g.controller),binding(g.rules),binding(g.vault),g.addr("relayer"),g.addr("oracle"),g.addr("admin"),g.symbol,g.policy,ORIGIN);
        g.transfer("treasury",g.addr("userA"),CAnvilContractGate.units(1_000_000));g.transfer("treasury",g.addr("userB"),CAnvilContractGate.units(1_000_000));g.cash("treasury",VaultAction.FUND_HOUSE,CAnvilContractGate.units(100));
        g.cash("userA",VaultAction.DEPOSIT,CAnvilContractGate.units(1100));g.cash("userB",VaultAction.DEPOSIT,CAnvilContractGate.units(1000));
        positionA=CAnvilContractGate.hash(run+"_POSITION_A");positionB=CAnvilContractGate.hash(run+"_POSITION_B");
        userAction("userA",positionA,0,0,0,100);userAction("userB",positionB,0,0,0,100);operationClose=userAction("userA",positionA,4,1,1,110);
        g.cashState(CAnvilContractGate.units(1200),CAnvilContractGate.units(1000),BigInteger.ZERO);check(g.callNum(g.vault,"totalUnpaidProfit()").equals(CAnvilContractGate.units(900)),"BOOTSTRAP_CLAIM");
        g.save("capital-variant.json",CAnvilContractGate.JSON.createObjectNode().put("scope","EXPLICIT_SMALL_HOUSE_READ_FIXTURE_NOT_DEFAULT_H10M_ACCEPTANCE").put("totalSupplyTokens","20000000").put("houseTokens","100").put("userAllocationTokens","2000000").put("treasuryUnallocatedTokens","17999900").put("realMetaMask",false).put("positionA",positionA).put("positionB",positionB).put("claimId","1"));
    }
    private CCustodyIdentity.Contract binding(String address)throws Exception{return new CCustodyIdentity.Contract(address,Hash.sha3(g.rpc("eth_getCode",address,"latest").asText()));}
    /** Minimal direct-owner fixture transactions, not a product Sender/Worker or repeated C5 scenario. */
    private String userAction(String role,String position,int action,int version,int nonce,int price)throws Exception{
        BigInteger now=CAnvilContractGate.number(g.rpc("eth_getBlockByNumber","latest",false).path("timestamp")),entry=BigInteger.valueOf(100_00000000L),mark=BigInteger.valueOf(price).multiply(BigInteger.TEN.pow(8)),collateral=CAnvilContractGate.units(1000);
        BigInteger quantity=FuturesMath.quantity(collateral,10,entry);String op=CAnvilContractGate.hash(run+"_BOOTSTRAP_"+(++sequence));String account=g.call(g.controller,"accountRef(address)",CAnvilContractGate.addressWord(g.addr(role)));
        var report=new Report(op,position,account,g.symbol,BigInteger.valueOf(action),BigInteger.valueOf(version),BigInteger.ZERO,BigInteger.ZERO,BigInteger.TEN,quantity,entry,action==0?BigInteger.ZERO:collateral,collateral,mark,now,now.add(BigInteger.valueOf(30)),g.policy,g.controller);
        var intent=new Intent(g.addr(role),g.addr("relayer"),op,position,account,g.symbol,report.action(),report.expectedVersion(),BigInteger.ZERO,BigInteger.ZERO,BigInteger.TEN,action==0?BigInteger.ZERO:entry,report.collateralBefore(),collateral,mark,mark,quantity,quantity,BigInteger.valueOf(nonce),now,now.add(BigInteger.valueOf(120)),g.policy);
        String rd=g.crypto.reportDigest(report,CAnvilContractGate.CHAIN,g.rules),ud=g.crypto.intentDigest(intent,CAnvilContractGate.CHAIN,g.controller),signature=CAnvilContractGate.signature(rd,g.keys.get("oracle"));
        var math=FuturesMath.mark(FuturesMath.Side.LONG,quantity,entry,collateral,mark);BigInteger realized=action==4?math.realized():BigInteger.ZERO;
        List<CAnvilContractGate.Expected> expected=new ArrayList<>();expected.add(new CAnvilContractGate.Expected(g.rules,EmitterKind.RULES,new OperationApplied(op,position,report.action(),BigInteger.valueOf(version+1),mark,collateral,math.unrealized(),realized,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO)));
        expected.add(new CAnvilContractGate.Expected(g.rules,EmitterKind.RULES,new FuturesReportConsumed(op,now,report.validUntil())));
        if(action==4){expected.add(new CAnvilContractGate.Expected(g.vault,EmitterKind.VAULT,new ProfitClaimCreated(BigInteger.ONE,g.addr(role),position,op,CAnvilContractGate.units(900))));expected.add(new CAnvilContractGate.Expected(g.vault,EmitterKind.VAULT,new PositionSettled(op,position,g.addr(role),collateral,realized,BigInteger.ZERO,CAnvilContractGate.units(100),BigInteger.ONE)));}
        else expected.add(new CAnvilContractGate.Expected(g.vault,EmitterKind.VAULT,new CollateralLocked(op,position,g.addr(role),collateral,collateral)));
        expected.add(new CAnvilContractGate.Expected(g.controller,EmitterKind.CONTROLLER,new CUserIntentExecuted(op,position,g.addr(role),ud,rd,report.action(),intent.nonce(),true)));
        g.expect(g.send(g.keys.get(role),g.controller,g.abi.encode(new Direct(report,signature,intent)),true),expected);return op;
    }
    private void writeServerProperties()throws Exception{
        Properties p=new Properties();p.setProperty("app.c-futures.enabled","true");p.setProperty("app.c-futures.chain-id",identity.chainId().toString());p.setProperty("app.c-futures.genesis-hash",identity.genesisHash());
        for(var pair:List.of(Map.entry("token",identity.token()),Map.entry("controller",identity.controller()),Map.entry("rules",identity.rules()),Map.entry("vault",identity.vault()))){p.setProperty("app.c-futures."+pair.getKey(),pair.getValue().address());p.setProperty("app.c-futures."+pair.getKey()+"-code-hash",pair.getValue().codeHash());}
        p.setProperty("app.c-futures.relayer",identity.relayer());p.setProperty("app.c-futures.oracle",identity.oracle());p.setProperty("app.c-futures.pause-admin",identity.pauseAdmin());p.setProperty("app.c-futures.symbol-hash",identity.symbolHash());p.setProperty("app.c-futures.policy-hash",identity.policyHash());p.setProperty("app.c-futures.origin",ORIGIN);p.setProperty("app.c-futures.rpc-uri","http://anvil:8545");
        p.setProperty("spring.datasource.url",jdbc("c_live_api_server"));p.setProperty("spring.datasource.username",apiUser);p.setProperty("spring.datasource.password",apiPassword);p.setProperty("spring.datasource.driver-class-name","org.postgresql.Driver");p.setProperty("spring.datasource.hikari.maximum-pool-size","3");p.setProperty("spring.datasource.hikari.minimum-idle","0");p.setProperty("spring.datasource.hikari.connection-timeout","3000");
        p.setProperty("spring.jpa.hibernate.ddl-auto","none");p.setProperty("spring.jpa.open-in-view","false");p.setProperty("spring.jpa.show-sql","false");p.setProperty("spring.sql.init.mode","never");p.setProperty("spring.jackson.serialization.write-dates-as-timestamps","false");
        p.setProperty("app.jwt.secret",jwtSecret);p.setProperty("app.jwt.validity-ms","120000");p.setProperty("app.c-gate.run-id",run);p.setProperty("app.c-gate.source-identity",source);p.setProperty("app.c-gate.db-name",database);p.setProperty("app.c-gate.api-user",apiUser);p.setProperty("app.c-gate.marker",marker);
        p.setProperty("server.address","0.0.0.0");p.setProperty("server.port","8080");p.setProperty("server.tomcat.threads.max","8");p.setProperty("server.tomcat.threads.min-spare","1");p.setProperty("logging.level.root","WARN");p.setProperty("logging.level.org.hibernate.SQL","OFF");p.setProperty("spring.main.banner-mode","off");
        try(var out=Files.newOutputStream(configDir.resolve("application.properties"),StandardOpenOption.CREATE_NEW)){p.store(out,"NEW OWNED SELECTED C CONTEXT; NO USER EOA KEYS");}
    }
    private void awaitServer()throws Exception{
        long end=System.nanoTime()+Duration.ofSeconds(75).toNanos();Path ready=serverEvidence.resolve("server-ready.json");
        while(!Files.isRegularFile(ready)){check(System.nanoTime()<end,"SERVER_READY_TIMEOUT");Thread.sleep(100);}
        JsonNode value=CAnvilContractGate.JSON.readTree(Files.readAllBytes(ready));check(value.path("runId").asText().equals(run)&&value.path("sourceIdentity").asText().equals(source)&&value.path("dbName").asText().equals(database)&&value.path("dbUser").asText().equals(apiUser)&&value.path("markerSha256").asText().equals(CWalletLinkStore.digest(marker)),"SERVER_READY_IDENTITY");
        check(api("GET","/config",null,null,null).status==401,"UNAUTHENTICATED_HTTP");
    }
    private void apiAcceptance()throws Exception{
        var provider=new JwtTokenProvider(jwtSecret,120_000);Map<Long,String> tokens=new HashMap<>();for(long uid=1;uid<=4;uid++)tokens.put(uid,provider.createToken(uid,"trial-"+uid));
        check(api("GET","/config","broken.token",null,null).status==401,"BROKEN_JWT");check(api("GET","/config",new JwtTokenProvider("X".repeat(64),120_000).createToken(1L,"wrong-secret"),null,null).status==401,"OTHER_SECRET_JWT");check(api("GET","/config",provider.createToken(99L,"missing"),null,null).status==401,"MISSING_USER_JWT");
        Api cfg=api("GET","/config",tokens.get(1L),null,null);check(cfg.status==200&&cfg.body.path("value").path("deploymentId").asText().equals(identity.deploymentId()),"HTTP_CONFIG_IDENTITY");scope(cfg.body);
        check(api("POST","/wallet/challenges",tokens.get(1L),"http://wrong-origin:5173",Map.of("wallet",g.addr("userA"))).status==403,"ORIGIN_BLOCKED");
        Api c1=challenge(tokens.get(1L),g.addr("userA")),c2=challenge(tokens.get(2L),g.addr("userA"));
        check(api("POST","/wallet/link",tokens.get(2L),ORIGIN,Map.of("challengeId",c1.body.path("id").asText(),"signature",personal(c1.body.path("message").asText(),g.keys.get("userA")))).status==404,"CROSS_USER_PROOF");
        check(link(tokens.get(1L),c1,g.keys.get("userB")).status==400,"WRONG_SIGNATURE");
        ExecutorService workers=Executors.newFixedThreadPool(2);Api a,b;
        try{CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);Future<Api> one=workers.submit(()->{ready.countDown();start.await();return link(tokens.get(1L),c1,g.keys.get("userA"));});Future<Api> two=workers.submit(()->{ready.countDown();start.await();return link(tokens.get(2L),c2,g.keys.get("userA"));});check(ready.await(3,TimeUnit.SECONDS),"LINK_RACE_READY");start.countDown();a=one.get(20,TimeUnit.SECONDS);b=two.get(20,TimeUnit.SECONDS);}finally{workers.shutdownNow();}
        check((a.status==200&&b.status==409)||(a.status==409&&b.status==200),"WALLET_UNIQUE_RACE");long userA=a.status==200?1:2,userB=userA==1?2:1;Api winner=userA==1?c1:c2;
        check(link(tokens.get(userA),winner,g.keys.get("userA")).status==409,"PROOF_REPLAY");Api cB=challenge(tokens.get(userB),g.addr("userB"));check(link(tokens.get(userB),cB,g.keys.get("userB")).status==200,"SECOND_OWNER_LINK");
        check(api("GET","/account",tokens.get(3L),null,null).status==404,"ADMIN_NO_WALLET_BYPASS");
        Api accountA=api("GET","/account",tokens.get(userA),null,null);check(accountA.status==200,"ACCOUNT_HTTP");scope(accountA.body);String at="0x"+new BigInteger(accountA.body.path("block").path("number").asText()).toString(16);
        Map<String,Object> block=Map.of("blockHash",accountA.body.path("block").path("hash").asText(),"requireCanonical",true);JsonNode observed=accountA.body.path("value");
        check(observed.path("owner").asText().equals(g.addr("userA"))&&observed.path("free").asText().equals(CAnvilContractGate.units(1200).toString())&&observed.path("totalUnpaidProfit").asText().equals(CAnvilContractGate.units(900).toString()),"ACCOUNT_AMOUNTS");
        check(observed.path("free").asText().equals(pinnedNum(g.vault,"freeBalance(address)",CAnvilContractGate.addressWord(g.addr("userA")),block).toString()),"PINNED_FREE");
        check(observed.path("actualVaultTokens").asText().equals(pinnedNum(g.token,"balanceOf(address)",CAnvilContractGate.addressWord(g.vault),block).toString()),"PINNED_VAULT_TOKENS");
        check(g.rpc("eth_getBlockByNumber",at,false).path("hash").asText().equals(accountA.body.path("block").path("hash").asText()),"DRIVER_HEADER_RECHECK");
        Api position=api("GET","/positions/"+positionA,tokens.get(userA),null,null);check(position.status==200&&position.body.path("value").path("status").asText().equals("CLOSED")&&position.body.path("value").path("lockedCollateral").asText().equals("0"),"TERMINAL_POSITION_HTTP");scope(position.body);
        Api claim=api("GET","/claims/1",tokens.get(userA),null,null);check(claim.status==200&&claim.body.path("value").path("originalAmount").asText().equals(CAnvilContractGate.units(900).toString())&&claim.body.path("value").path("paidAmount").asText().equals("0"),"CLAIM_HTTP");scope(claim.body);
        check(api("GET","/positions/"+positionA,tokens.get(userB),null,null).status==404&&api("GET","/claims/1",tokens.get(userB),null,null).status==404,"FOREIGN_OWNER_HTTP");check(api("GET","/positions/"+positionB,tokens.get(userB),null,null).status==200,"OWN_ACTIVE_POSITION_HTTP");
        rollbackProof(tokens.get(4L));
        g.save("api-acceptance.json",CAnvilContractGate.JSON.createObjectNode().put("actualHttpJwtFilter",true).put("actualJpaUserRepository",true).put("userAAccount",userA).put("userBAccount",userB).put("walletRaceSuccessCount",1).put("ownerReadScope","ACTUAL_RPC_SAME_PROVIDER_PINNED_BLOCK_HASH_RECHECK").put("completeCoverage",false).put("realBrowser",false));
    }
    private void rollbackProof(String token)throws Exception{
        Api proof=challenge(token,g.addr("proofC"));
        try(Connection c=ownerConnection();Statement s=c.createStatement()){
            s.setQueryTimeout(3);s.execute("CREATE FUNCTION public.c_live_fail_proof_consume() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.user_id=4 AND NEW.proof_signature IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='40001',MESSAGE='C_LIVE_CONTROLLED_CONSUME_FAILURE'; END IF; RETURN NEW; END $$");
            s.execute("CREATE TRIGGER c_live_consume_fault BEFORE UPDATE ON tms_c_identity.wallet_challenges FOR EACH ROW EXECUTE FUNCTION public.c_live_fail_proof_consume()");
        }
        Api result=link(token,proof,g.keys.get("proofC"));check(result.status==503&&result.body.path("code").asText().equals("C_WALLET_STORAGE"),"CONTROLLED_ROLLBACK_HTTP");
        try(Connection c=ownerConnection();Statement s=c.createStatement()){
            s.setQueryTimeout(3);try(ResultSet r=s.executeQuery("SELECT count(*) FROM tms_c_identity.wallet_links WHERE user_id=4")){check(r.next()&&r.getInt(1)==0,"ROLLBACK_LINK_ABSENT");}
            try(PreparedStatement p=c.prepareStatement("SELECT consumed,proof_signature FROM tms_c_identity.wallet_challenges WHERE id=?")){p.setString(1,proof.body.path("id").asText());try(ResultSet r=p.executeQuery()){check(r.next()&&!r.getBoolean(1)&&r.getString(2)==null,"ROLLBACK_PROOF_UNCONSUMED");}}
        }
        g.save("controlled-pg-rollback.json",CAnvilContractGate.JSON.createObjectNode().put("fixtureUserId",4).put("insertAndConsumeRolledBack",true).put("faultInstalledOnlyInNewFixture",true).put("automaticRetry",false));
    }
    private void databaseFinal()throws Exception{
        try(Connection c=ownerConnection();Statement s=c.createStatement()){
            s.setQueryTimeout(3);try(ResultSet r=s.executeQuery("SELECT value FROM public.c_live_preservation_sentinel")){check(r.next()&&r.getString(1).equals("PRESERVE_C_LIVE_SOURCE_AND_USERS")&&!r.next(),"SENTINEL");}
            try(ResultSet r=s.executeQuery("SELECT count(*) FROM public.users")){check(r.next()&&r.getInt(1)==4,"USERS_PRESERVED");}
            try(ResultSet r=s.executeQuery("SELECT count(*),count(DISTINCT wallet) FROM tms_c_identity.wallet_links")){check(r.next()&&r.getInt(1)==2&&r.getInt(2)==2,"LINKS_UNIQUE");}
        }
        // The C read API must not mutate any financial state while serving identity and snapshots.
        g.cashState(CAnvilContractGate.units(1200),CAnvilContractGate.units(1000),BigInteger.ZERO);check(g.callNum(g.vault,"totalUnpaidProfit()").equals(CAnvilContractGate.units(900)),"FINAL_UNPAID_UNCHANGED");
    }
    private BigInteger pinnedNum(String target,String signature,String arg,Map<String,Object> block)throws Exception{return CAnvilContractGate.wordNum(g.rpc("eth_call",Map.of("to",target,"data",CAnvilContractGate.selector(signature)+arg),block).asText(),0);}
    private Api challenge(String token,String wallet)throws Exception{Api c=api("POST","/wallet/challenges",token,ORIGIN,Map.of("wallet",wallet));check(c.status==200&&c.body.path("deploymentId").asText().equals(identity.deploymentId())&&c.body.path("wallet").asText().equals(wallet),"CHALLENGE_HTTP");return c;}
    private Api link(String token,Api challenge,ECKeyPair key)throws Exception{return api("POST","/wallet/link",token,ORIGIN,Map.of("challengeId",challenge.body.path("id").asText(),"signature",personal(challenge.body.path("message").asText(),key)));}
    private Api api(String method,String path,String token,String origin,Map<String,Object> body)throws Exception{
        check(path.matches("/(?:config|account|wallet(?:/challenges|/link)?|positions/0x[0-9a-f]{64}|claims/[0-9]+)"),"API_PATH");
        var request=HttpRequest.newBuilder(URI.create("http://api:8080/api/c-futures"+path)).timeout(Duration.ofSeconds(15));if(token!=null)request.header("Authorization","Bearer "+token);if(origin!=null)request.header("Origin",origin);
        if(method.equals("POST"))request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(CAnvilContractGate.JSON.writeValueAsBytes(body)));else request.GET();
        var response=CAnvilContractGate.singleAttempt(()->http.sendAsync(request.build(),info->new CAnvilContractGate.CappedBody()),15_000);
        JsonNode parsed=CAnvilContractGate.JSON.readTree(response.body());check(parsed!=null&&parsed.isObject(),"API_JSON");return new Api(response.statusCode(),parsed);
    }
    private static String personal(String message,ECKeyPair key){var s=Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8),key);byte[] out=new byte[65];System.arraycopy(s.getR(),0,out,0,32);System.arraycopy(s.getS(),0,out,32,32);System.arraycopy(s.getV(),0,out,64,1);return "0x"+HexFormat.of().formatHex(out);}
    private static void scope(JsonNode json){check(json.path("sameProviderHeaderRechecked").isBoolean()&&json.path("sameProviderHeaderRechecked").asBoolean()&&json.path("completeCoverage").isBoolean()&&!json.path("completeCoverage").asBoolean()&&json.path("globalCanonicalVerified").isBoolean()&&!json.path("globalCanonicalVerified").asBoolean()&&json.path("verificationScope").asText().equals("SINGLE_PROVIDER_PINNED_BLOCK_HASH_RECHECK_NOT_SCANNER_OR_FINALITY"),"HTTP_VERIFICATION_SCOPE");}
    private static String secret(String key){String value=System.getenv(key);check(value!=null&&value.matches("[0-9a-f]{64}"),"PRIVATE_SETTING");return value;}
    static void selfTest(){
        check(ORIGIN.equals("http://localhost:5173"),"FIXED_ORIGIN");
        check(!CCustodyIdentity.USER_DOMAIN.equals(CCustodyIdentity.PRICE_DOMAIN),"SEPARATE_DOMAINS");
        check(safeCode(new SQLException("private-db-password")).equals("C_LIVE_SAFE_FAILURE"),"SQL_REDACTION");
        check(safeCode(new CCustodyFailure("C_RPC_TIMEOUT",503)).equals("C_RPC_TIMEOUT"),"SAFE_RPC_CODE");
        check(CWalletLinkStore.digest("marker").length()==64,"MARKER_HASH");
    }
    private static String safeCode(Exception error){if(error instanceof CCustodyFailure f)return f.code();String m=error.getMessage();return m!=null&&m.matches("C(?:5A1|_LIVE)_[A-Z0-9_]+")?m:"C_LIVE_SAFE_FAILURE";}
    private static void check(boolean value,String code){if(!value)throw new IllegalStateException("C_LIVE_"+code);}
}
