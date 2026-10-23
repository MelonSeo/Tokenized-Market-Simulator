import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pricetrack.exchange.custody.abi.*;
import com.pricetrack.exchange.custody.abi.CAbiModels.*;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.*;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

/** New fixture trial-key bootstrap/HTTP client only. Never placed in the keyless Spring/Oracle fixtures. */
public final class CDirectDemoDriver {
    final Path fixture,privateBase,appConfig,oracleConfig,control;final String phase;
    final CAnvilContractGate g;final JsonNode manifest;final String run,source,marker,database,owner,api,origin;
    final String ownerPassword,apiPassword,jwtSecret,loginPassword,oracleToken;
    CCustodyIdentity identity;long httpSequence;
    CDirectDemoDriver(String phase,Path fixture,Path privateBase,Path appConfig,Path oracleConfig,Path control)throws Exception{
        check(Set.of("bootstrap","verify").contains(phase),"PHASE");this.phase=phase;this.fixture=fixture;this.privateBase=privateBase;this.appConfig=appConfig;this.oracleConfig=oracleConfig;this.control=control;
        for(Path p:List.of(fixture,privateBase,appConfig,oracleConfig,control))check(Files.isDirectory(p),"DIRECTORY");
        Path phaseEvidence=privateBase.resolve(phase);Files.createDirectory(phaseEvidence);g=new CAnvilContractGate(fixture,phaseEvidence);
        manifest=g.read(fixture.resolve("manifest.json"));run=manifest.path("runId").asText();source=manifest.path("sourceIdentity").asText();marker=manifest.path("marker").asText();origin=manifest.path("origin").asText();
        check(manifest.path("scope").asText().equals("C_DIRECT_DEMO_NEW_OWNED_FIXTURE")&&run.matches("cdemo-[0-9a-f]{16}")&&source.matches("[0-9a-f]{64}")&&marker.matches("[0-9a-f]{64}"),"MANIFEST");
        String suffix=run.substring(6);database="cdemo_"+suffix;owner="cdowner_"+suffix;api="cdapi_"+suffix;CCustodyIdentity.validOrigin(origin);
        check(manifest.path("database").asText().equals(database)&&manifest.path("databaseOwner").asText().equals(owner)&&manifest.path("apiUser").asText().equals(api),"DATABASE_IDENTITY");
        check(manifest.path("presentationLocalOnly").isBoolean()&&manifest.path("presentationLocalOnly").asBoolean()
            &&manifest.path("syntheticTickMs").isIntegralNumber()&&manifest.path("syntheticTickMs").asInt()==3000,"LOCAL_PRESENTATION_CADENCE");
        ownerPassword=secret("C_DEMO_OWNER_PASSWORD");apiPassword=secret("C_DEMO_API_PASSWORD");jwtSecret=secret("C_DEMO_JWT_SECRET");loginPassword=secret("C_DEMO_LOGIN_PASSWORD");oracleToken=secret("C_DEMO_ORACLE_TOKEN");
        check(Set.of(ownerPassword,apiPassword,jwtSecret,loginPassword,oracleToken).size()==5&&oracleToken.matches("[0-9a-f]{64}"),"SECRET_SEPARATION");
    }
    public static void main(String[] args){
        String stage="ARGS";CDirectDemoDriver d=null;
        try{
            CAnvilContractGate.verifyTransportPolicy();
            if(args.length==2&&args[0].equals("--self-test")){selfTest(Path.of(args[1]));System.out.println("C_DIRECT_DEMO_DRIVER_SELF_TEST_PASS");return;}
            check(args.length==6,"ARGS");d=new CDirectDemoDriver(args[0],Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),Path.of(args[4]),Path.of(args[5]));
            stage="SOURCE_IDENTITY";d.g.verifySourceMetadata();
            if(d.phase.equals("bootstrap")){stage="CHAIN_BOOTSTRAP";d.chainBootstrap();stage="POSTGRES_BOOTSTRAP";d.databaseBootstrap();stage="KEYLESS_CONFIG";d.writeConfig();}
            else{stage="LOAD_OWNED_FIXTURE";d.loadBootstrap();stage="ACTUAL_HTTP_OWNER_DIRECT";d.verifyHttp();}
            d.g.save("phase-result.json",CAnvilContractGate.JSON.createObjectNode().put("runId",d.run).put("sourceIdentity",d.source).put("phase",d.phase).put("result","PASS").put("realMetaMask",false).put("productSynthetic",d.phase.equals("verify")).put("completeCoverage",false));
            System.out.println("C_DIRECT_DEMO_"+d.phase.toUpperCase(Locale.ROOT)+"_PASS");
        }catch(Exception safe){
            if(d!=null)try{var o=CAnvilContractGate.JSON.createObjectNode().put("stage",stage);var types=o.putArray("causeTypes");Throwable next=safe;for(int n=0;next!=null&&n<8;n++,next=next.getCause()){types.add(next.getClass().getName());if(next instanceof SQLException s&&s.getSQLState()!=null&&s.getSQLState().matches("[A-Z0-9]{5}"))o.put("sqlState",s.getSQLState());}d.g.save("phase-failure.json",o);}catch(Exception ignored){}
            System.err.println("C_DIRECT_DEMO_DRIVER_STOP_NO_RETRY");System.exit(1);
        }
    }
    void chainBootstrap()throws Exception{
        for(String role:List.of("treasury","admin","relayer","oracle","userA","userB"))g.keys.put(role,Keys.createEcKeyPair(new SecureRandom()));
        check(g.keys.values().stream().map(CAnvilContractGate::address).distinct().count()==6,"ROLE_KEYS");
        ObjectNode keys=CAnvilContractGate.JSON.createObjectNode(),addresses=CAnvilContractGate.JSON.createObjectNode();
        for(var e:g.keys.entrySet()){keys.put(e.getKey(),String.format("%064x",e.getValue().getPrivateKey()));addresses.put(e.getKey(),CAnvilContractGate.address(e.getValue()));}
        Files.write(privateBase.resolve("keys.private.json"),CAnvilContractGate.JSON.writeValueAsBytes(keys),StandardOpenOption.CREATE_NEW);
        Files.write(privateBase.resolve("addresses.json"),CAnvilContractGate.JSON.writeValueAsBytes(addresses),StandardOpenOption.CREATE_NEW);
        check(CAnvilContractGate.number(g.rpc("eth_chainId")).equals(CAnvilContractGate.CHAIN),"CHAIN_ID");g.genesis=g.rpc("eth_getBlockByNumber","0x0",false).path("hash").asText();
        for(var pair:g.keys.values())g.rpc("anvil_setBalance",CAnvilContractGate.address(pair),CAnvilContractGate.hex(CAnvilContractGate.units(100)));
        g.symbol=Hash.sha3String("mSEC");g.policy=Hash.sha3String("TMS_C_FIFO_CLAIMS_MMR500_GRACE60_V1");
        g.token=g.deploy("CMockCollateral",CAnvilContractGate.words(CAnvilContractGate.addressWord(g.addr("treasury"))),g.keys.get("treasury"));
        g.controller=g.deploy("CCollateralizedFuturesController",CAnvilContractGate.words(CAnvilContractGate.addressWord(g.token),CAnvilContractGate.addressWord(g.addr("relayer")),CAnvilContractGate.addressWord(g.addr("oracle")),CAnvilContractGate.addressWord(g.addr("admin")),g.symbol,g.policy),g.keys.get("treasury"));
        g.rules=g.callAddress(g.controller,"rules()");g.vault=g.callAddress(g.controller,"vault()");
        for(var pair:Map.of("CMockCollateral",g.token,"CCollateralizedFuturesController",g.controller,"FuturesEngine",g.rules,"CMarginVault",g.vault).entrySet())g.verifyRuntime(pair.getKey(),pair.getValue());
        identity=new CCustodyIdentity(CAnvilContractGate.CHAIN,g.genesis,binding(g.token),binding(g.controller),binding(g.rules),binding(g.vault),g.addr("relayer"),g.addr("oracle"),g.addr("admin"),g.symbol,g.policy,origin);
        check(g.callAddress(g.rules,"executor()").equals(g.controller)&&g.callAddress(g.rules,"priceSigner()").equals(identity.oracle())&&g.callAddress(g.vault,"controller()").equals(g.controller)&&g.callAddress(g.vault,"asset()").equals(g.token),"ROLE_BINDING");
        g.transfer("treasury",g.addr("userA"),CAnvilContractGate.units(1_000_000));g.transfer("treasury",g.addr("userB"),CAnvilContractGate.units(1_000_000));g.cash("treasury",VaultAction.FUND_HOUSE,CAnvilContractGate.units(10_000_000));
        CAnvilContractGate.eq(g.balance(g.addr("treasury")),CAnvilContractGate.units(8_000_000));g.cashState(BigInteger.ZERO,BigInteger.ZERO,CAnvilContractGate.units(10_000_000));
        Files.write(privateBase.resolve("identity.json"),CAnvilContractGate.JSON.writeValueAsBytes(identity),StandardOpenOption.CREATE_NEW);
        g.save("capital.json",CAnvilContractGate.JSON.createObjectNode().put("supply","20000000").put("house","10000000").put("userAllocations","2000000").put("treasuryReserve","8000000").put("trialEoaOnly",true));
    }
    CCustodyIdentity.Contract binding(String address)throws Exception{return new CCustodyIdentity.Contract(address,Hash.sha3(g.rpc("eth_getCode",address,"latest").asText()));}
    Connection ownerConnection()throws SQLException{return DriverManager.getConnection(jdbc("bootstrap"),owner,ownerPassword);}
    Connection apiConnection()throws SQLException{return DriverManager.getConnection(jdbc("verify"),api,apiPassword);}
    String jdbc(String name){return "jdbc:postgresql://pg:5432/"+database+"?sslmode=disable&connectTimeout=3&socketTimeout=5&ApplicationName=cdemo_"+name;}
    void databaseBootstrap()throws Exception{
        try(Connection c=ownerConnection();Statement s=c.createStatement()){
            s.setQueryTimeout(3);try(ResultSet r=s.executeQuery("SELECT current_database(),current_user,(SELECT count(*) FROM information_schema.tables WHERE table_schema='public')")){check(r.next()&&r.getString(1).equals(database)&&r.getString(2).equals(owner)&&r.getInt(3)==0,"FRESH_DATABASE");}
            s.execute("SET log_statement='none'; SET log_min_error_statement='panic'; SET log_parameter_max_length_on_error=0; SET log_parameter_max_length=0");
            s.execute("CREATE TABLE public.c_direct_demo_identity(run_id text NOT NULL,marker text NOT NULL); CREATE TABLE public.c_direct_demo_sentinel(value text PRIMARY KEY); INSERT INTO public.c_direct_demo_sentinel VALUES('PRESERVE_C_DIRECT_DEMO'); CREATE TABLE public.users(id BIGSERIAL PRIMARY KEY,login_id VARCHAR(50) UNIQUE,email VARCHAR(255) UNIQUE,password_hash VARCHAR(255),email_verified BOOLEAN NOT NULL DEFAULT FALSE,google_sub VARCHAR(255) UNIQUE,nickname VARCHAR(255) NOT NULL,role VARCHAR(20) NOT NULL,wallet_address VARCHAR(255),created_at TIMESTAMP WITH TIME ZONE NOT NULL)");
            try(var p=c.prepareStatement("INSERT INTO public.c_direct_demo_identity VALUES(?,?)")){p.setString(1,run);p.setString(2,marker);p.executeUpdate();}
            String passwordHash=new BCryptPasswordEncoder().encode(loginPassword);
            for(int n=1;n<=2;n++)try(var p=c.prepareStatement("INSERT INTO public.users(id,login_id,password_hash,nickname,role,created_at) VALUES(?,?,?,?,'USER',CURRENT_TIMESTAMP)")){p.setInt(1,n);p.setString(2,n==1?"c-demo-user":"c-demo-other");p.setString(3,passwordHash);p.setString(4,"C demo "+n);p.executeUpdate();}
            for(String name:List.of("c-wallet-schema.sql","c-consent-schema.sql","c-direct-execution-schema.sql","c-direct-demo-synthetic-schema.sql"))s.execute(schema(name));
            s.execute("CREATE ROLE "+api+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '"+apiPassword+"'; REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT CONNECT ON DATABASE "+database+" TO "+api+"; GRANT USAGE ON SCHEMA public,tms_c_identity,tms_c_execution TO "+api);
            s.execute("GRANT SELECT,UPDATE(id) ON public.users TO "+api+"; GRANT SELECT ON public.c_direct_demo_identity TO "+api+"; GRANT SELECT,INSERT ON tms_c_identity.wallet_links,tms_c_identity.wallet_challenges TO "+api+"; GRANT UPDATE(consumed,proof_signature) ON tms_c_identity.wallet_challenges TO "+api+"; GRANT SELECT,INSERT ON tms_c_execution.drafts,tms_c_execution.consents,tms_c_execution.direct_execution_claims,tms_c_execution.direct_executions TO "+api+"; GRANT SELECT,INSERT,UPDATE ON public.market_candles,public.synthetic_market_state TO "+api+"; GRANT USAGE,SELECT ON SEQUENCE public.market_candles_id_seq TO "+api);
            s.execute("CREATE FUNCTION public.c_direct_reject_user_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION USING ERRCODE='55000',MESSAGE='C_DIRECT_USERS_READ_ONLY'; END $$; CREATE TRIGGER c_direct_users_readonly BEFORE UPDATE OR DELETE OR TRUNCATE ON public.users FOR EACH STATEMENT EXECUTE FUNCTION public.c_direct_reject_user_change()");
            try(ResultSet r=s.executeQuery("SELECT bool_and(auth_method='scram-sha-256') FROM pg_hba_file_rules WHERE type IN ('host','hostssl','hostnossl')")){check(r.next()&&r.getBoolean(1),"HOST_SCRAM");}
            try(var p=c.prepareStatement("SELECT rolpassword LIKE 'SCRAM-SHA-256$%' FROM pg_authid WHERE rolname=?")){p.setString(1,api);try(var r=p.executeQuery()){check(r.next()&&r.getBoolean(1),"API_SCRAM");}}
        }
        try(Connection c=apiConnection();Statement s=c.createStatement()){
            try{s.executeUpdate("UPDATE public.users SET id=id WHERE id=1");throw new IllegalStateException("C_DIRECT_DEMO_USERS_MUTABLE");}catch(SQLException expected){check("55000".equals(expected.getSQLState()),"USERS_READ_ONLY");}
            try{s.execute("CREATE TABLE public.forbidden(id int)");throw new IllegalStateException("C_DIRECT_DEMO_API_DDL_ALLOWED");}catch(SQLException expected){check("42501".equals(expected.getSQLState()),"API_DDL_DENIED");}
        }
        try(Connection ignored=DriverManager.getConnection(jdbc("badpassword"),api,"wrong-"+apiPassword)){throw new IllegalStateException("C_DIRECT_DEMO_BAD_PASSWORD_ACCEPTED");}catch(SQLException expected){check("28P01".equals(expected.getSQLState()),"SCRAM_AUTHENTICATION");}
        g.save("postgres-bootstrap.json",CAnvilContractGate.JSON.createObjectNode().put("database",database).put("apiUser",api).put("scramVerified",true).put("userWritesDenied",true).put("financialLedgerTables",0));
    }
    String schema(String name)throws Exception{
        check(name.matches("c-[a-z-]+\\.sql"),"SCHEMA_NAME");byte[] raw=Files.readAllBytes(fixture.resolve(name));check(raw.length>0&&raw.length<=65536,"SCHEMA_SIZE");
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));check(hash.equals(manifest.path("sqlHashes").path(name).asText()),"SCHEMA_HASH");return new String(raw,StandardCharsets.UTF_8);
    }
    void writeConfig()throws Exception{
        Properties p=new Properties();p.setProperty("app.c-demo.enabled","true");p.setProperty("app.c-futures.enabled","true");p.setProperty("app.c-futures.direct.enabled","true");
        p.setProperty("app.c-futures.chain-id",identity.chainId().toString());p.setProperty("app.c-futures.genesis-hash",identity.genesisHash());
        for(var e:Map.of("token",identity.token(),"controller",identity.controller(),"rules",identity.rules(),"vault",identity.vault()).entrySet()){p.setProperty("app.c-futures."+e.getKey(),e.getValue().address());p.setProperty("app.c-futures."+e.getKey()+"-code-hash",e.getValue().codeHash());}
        p.setProperty("app.c-futures.relayer",identity.relayer());p.setProperty("app.c-futures.oracle",identity.oracle());p.setProperty("app.c-futures.pause-admin",identity.pauseAdmin());p.setProperty("app.c-futures.symbol-hash",identity.symbolHash());p.setProperty("app.c-futures.policy-hash",identity.policyHash());p.setProperty("app.c-futures.origin",origin);p.setProperty("app.c-futures.rpc-uri","http://anvil:8545");p.setProperty("app.c-futures.direct.oracle-token-file","/demo-config/oracle-token.private");
        p.setProperty("spring.datasource.url",jdbc("server"));p.setProperty("spring.datasource.username",api);p.setProperty("spring.datasource.password",apiPassword);p.setProperty("spring.datasource.driver-class-name","org.postgresql.Driver");p.setProperty("spring.datasource.hikari.maximum-pool-size","4");p.setProperty("spring.datasource.hikari.minimum-idle","0");p.setProperty("spring.datasource.hikari.connection-timeout","3000");
        p.setProperty("spring.jpa.hibernate.ddl-auto","validate");p.setProperty("spring.jpa.open-in-view","false");p.setProperty("spring.jpa.show-sql","false");p.setProperty("spring.sql.init.mode","never");p.setProperty("spring.jackson.serialization.write-dates-as-timestamps","false");p.setProperty("spring.web.resources.static-locations","file:/demo-web/");
        p.setProperty("app.jwt.secret",jwtSecret);p.setProperty("app.jwt.validity-ms","900000");p.setProperty("app.c-demo.run-id",run);p.setProperty("app.c-demo.source-identity",source);p.setProperty("app.c-demo.db-name",database);p.setProperty("app.c-demo.api-user",api);p.setProperty("app.c-demo.marker",marker);p.setProperty("app.c-demo.lifetime-seconds",manifest.path("lifetimeSeconds").asText("900"));
        // Local presentation cadence only. Product freshness=5s and Report TTL=30s are unchanged.
        p.setProperty("app.price.provider","simulated");p.setProperty("app.price.simulation.seed","20261023");p.setProperty("app.price.simulation.bootstrap-days","0");p.setProperty("app.price.update-interval-ms",manifest.path("syntheticTickMs").asText());p.setProperty("app.price.initial-delay-ms","1000");
        p.setProperty("server.address","0.0.0.0");p.setProperty("server.port","8080");p.setProperty("server.tomcat.threads.max","8");p.setProperty("server.tomcat.threads.min-spare","1");p.setProperty("logging.level.root","WARN");p.setProperty("logging.level.org.hibernate.SQL","OFF");p.setProperty("logging.level.org.hibernate.orm.jdbc.bind","OFF");p.setProperty("spring.main.banner-mode","off");
        try(var out=Files.newOutputStream(appConfig.resolve("application.properties"),StandardOpenOption.CREATE_NEW)){p.store(out,"new isolated C direct demo only; no EOA private keys");}
        Files.writeString(appConfig.resolve("oracle-token.private"),oracleToken,StandardCharsets.US_ASCII,StandardOpenOption.CREATE_NEW);
        Files.write(oracleConfig.resolve("identity.json"),CAnvilContractGate.JSON.writeValueAsBytes(identity),StandardOpenOption.CREATE_NEW);
        Files.writeString(oracleConfig.resolve("oracle-key.private"),String.format("%064x",g.keys.get("oracle").getPrivateKey()),StandardCharsets.US_ASCII,StandardOpenOption.CREATE_NEW);
        Files.writeString(oracleConfig.resolve("auth-token.private"),oracleToken,StandardCharsets.US_ASCII,StandardOpenOption.CREATE_NEW);
        g.save("bootstrap-ready.json",CAnvilContractGate.JSON.createObjectNode().put("runId",run).put("sourceIdentity",source).put("deploymentId",identity.deploymentId()).put("keylessServer",true).put("controller",identity.controller().address()).put("vault",identity.vault().address()));
    }
    void loadBootstrap()throws Exception{
        identity=CAnvilContractGate.JSON.readValue(Files.readAllBytes(privateBase.resolve("identity.json")),CCustodyIdentity.class);
        JsonNode keys=CAnvilContractGate.JSON.readTree(Files.readAllBytes(privateBase.resolve("keys.private.json")));
        for(String role:List.of("userA","userB"))g.keys.put(role,ECKeyPair.create(new BigInteger(keys.path(role).asText(),16)));
        g.token=identity.token().address();g.controller=identity.controller().address();g.rules=identity.rules().address();g.vault=identity.vault().address();g.genesis=identity.genesisHash();g.symbol=identity.symbolHash();g.policy=identity.policyHash();
        check(CAnvilContractGate.number(g.rpc("eth_chainId")).equals(identity.chainId())&&g.rpc("eth_getBlockByNumber","0x0",false).path("hash").asText().equals(identity.genesisHash()),"SAME_CHAIN");
    }
    record Response(int status,JsonNode body){}
    Response http(String method,String path,JsonNode body,String jwt)throws Exception{
        check(path.startsWith("/")&&!path.contains("..")&&!path.contains("://"),"HTTP_PATH");
        var request=HttpRequest.newBuilder(URI.create("http://app:8080"+path)).timeout(Duration.ofSeconds(10)).header("Origin",origin);
        if(jwt!=null)request.header("Authorization","Bearer "+jwt);
        if(body!=null)request.header("Content-Type","application/json");
        if(path.endsWith("/intents")&&method.equals("POST"))request.header("Idempotency-Key","cdemo-"+run+"-"+body.path("action").asText());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(CAnvilContractGate.JSON.writeValueAsBytes(body)));
        var response=CAnvilContractGate.singleAttempt(()->g.client.sendAsync(request.build(),info->new CAnvilContractGate.CappedBody()),10000);
        byte[] bytes=response.body();check(bytes.length<=CAnvilContractGate.BODY_LIMIT,"HTTP_BODY_CAP");
        JsonNode json;try{json=CAnvilContractGate.JSON.readTree(bytes);}catch(Exception nonJson){json=TextNode.valueOf(new String(bytes,StandardCharsets.UTF_8));}
        g.save("http-"+(++httpSequence)+".private.json",CAnvilContractGate.JSON.createObjectNode().put("path",path).put("status",response.statusCode()).set("body",json));
        return new Response(response.statusCode(),json);
    }
    String login(String name)throws Exception{var r=http("POST","/api/auth/login",CAnvilContractGate.JSON.createObjectNode().put("loginId",name).put("password",loginPassword),null);check(r.status==200&&r.body.path("tokenType").asText().equals("Bearer"),"LOGIN");String jwt=r.body.path("accessToken").asText();check(jwt.split("\\.").length==3,"JWT_FORMAT");return jwt;}
    void link(String role,String jwt)throws Exception{
        var challenge=http("POST","/api/c-futures/wallet/challenges",CAnvilContractGate.JSON.createObjectNode().put("wallet",g.addr(role)),jwt);check(challenge.status==200,"CHALLENGE");
        String message=challenge.body.path("message").asText();check(!message.isEmpty(),"CHALLENGE_MESSAGE");var s=Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8),g.keys.get(role));String signature=Numeric.toHexString(join(s.getR(),s.getS(),s.getV()));
        var linked=http("POST","/api/c-futures/wallet/link",CAnvilContractGate.JSON.createObjectNode().put("challengeId",challenge.body.path("id").asText()).put("signature",signature),jwt);check(linked.status==200&&linked.body.path("wallet").asText().equals(g.addr(role)),"WALLET_LINK");
    }
    JsonNode freshMarket(String jwt)throws Exception{
        var r=http("GET","/api/c-futures/direct/market",null,jwt);check(r.status==200&&r.body.path("provider").asText().equals("SIMULATED")&&r.body.path("priceStatus").asText().equals("SIMULATED")&&r.body.path("price").isTextual(),"PRODUCT_SYNTHETIC_MARKET");return r.body;
    }
    void verifyHttp()throws Exception{
        check(http("GET","/api/c-futures/config",null,null).status==401,"JWT_REQUIRED");
        check(http("GET","/api/me",null,null).status==401,"ME_JWT_REQUIRED");
        check(http("POST","/api/auth/login",CAnvilContractGate.JSON.createObjectNode().put("loginId","c-demo-user").put("password","wrong-password"),null).status==401,"PASSWORD_REQUIRED");
        check(http("GET","/c-demo.html",null,null).status==200,"STATIC_WEB");check(http("POST","/c-demo.html",null,null).status==401,"STATIC_GET_ONLY");
        String jwt=login("c-demo-user"),other=login("c-demo-other");
        var me=http("GET","/api/me",null,jwt);check(me.status==200&&me.body.path("id").asText().equals("1")&&me.body.path("loginId").asText().equals("c-demo-user")&&me.body.path("role").asText().equals("USER"),"JWT_ACTUAL_CURRENT_USER");
        link("userA",jwt);link("userB",other);
        var first=freshMarket(jwt);var next=nextMarketTick(jwt);check(Instant.parse(next.path("observedAt").asText()).isAfter(Instant.parse(first.path("observedAt").asText())),"PRODUCT_TICK_ADVANCING");
        // Real owner approve/deposit, with exact required allowance and actual token movement.
        g.cash("userA",VaultAction.DEPOSIT,CAnvilContractGate.units(3000));
        // The browser cannot advance on wallet receipts alone: exercise its actual server acknowledgement path.
        verifyCashReceipt(g.recordId-1,"APPROVE",jwt);verifyCashReceipt(g.recordId,"DEPOSIT",jwt);
        var account=http("GET","/api/c-futures/account",null,jwt);check(account.status==200&&account.body.path("value").path("free").asText().equals(CAnvilContractGate.units(3000).toString()),"DEPOSIT_ACCOUNT");
        JsonNode opened=execute("OPEN",null,jwt);String position=opened.path("intent").path("positionId").asText();
        check(http("GET","/api/c-futures/positions/"+position,null,other).status==404,"OTHER_USER_POSITION_DENIED");
        JsonNode closed=execute("CLOSE",position,jwt);
        BigInteger quantity=new BigInteger(closed.path("report").path("quantity").asText()),entry=new BigInteger(closed.path("report").path("entryPriceE8").asText()),price=new BigInteger(closed.path("report").path("priceE8").asText());
        var math=FuturesMath.mark(FuturesMath.Side.LONG,quantity,entry,CAnvilContractGate.units(1000),price);
        BigInteger withdraw=g.free(g.addr("userA"));check(withdraw.equals(CAnvilContractGate.units(3000).add(math.realized())),"CLOSE_FREE_AND_REALIZED");
        g.cash("userA",VaultAction.WITHDRAW,withdraw);verifyCashReceipt(g.recordId,"WITHDRAW",jwt);check(g.free(g.addr("userA")).signum()==0&&g.callNum(g.vault,"totalLocked()").signum()==0&&g.callNum(g.vault,"totalUnpaidProfit()").signum()==0,"TERMINAL_WITHDRAW");
        check(g.balance(g.addr("userA")).equals(CAnvilContractGate.units(1_000_000).add(math.realized())),"ACTUAL_OWNER_TOKENS");
        BigInteger house=CAnvilContractGate.units(10_000_000).subtract(math.realized());g.cashState(BigInteger.ZERO,BigInteger.ZERO,house);
        databaseFinal();
        g.save("direct-actual-result.json",CAnvilContractGate.JSON.createObjectNode().put("runId",run).put("sourceIdentity",source).put("result","PASS").put("scope","ACTUAL_C_OWNER_DIRECT_HTTP_SYNTHETIC_PG_ANVIL_NOT_METAMASK_OR_FULL_AUDIT").put("approveDeposit",true).put("open",true).put("close",true).put("freeWithdraw",true).put("realMetaMask",false).put("completeCoverage",false).put("realized",math.realized().toString()).put("finalWallet",g.balance(g.addr("userA")).toString()));
    }
    void verifyCashReceipt(long sequence,String kind,String jwt)throws Exception{
        Path path=g.output.resolve(String.format("%03d-receipt.observed.private.json",sequence));
        check(Files.isRegularFile(path)&&Files.size(path)<=CAnvilContractGate.BODY_LIMIT,"CASH_RECEIPT_EVIDENCE");
        var receipt=CAnvilContractGate.JSON.readTree(Files.readAllBytes(path));
        var observed=http("GET","/api/c-futures/direct/transactions/"+receipt.path("transactionHash").asText(),null,jwt);
        check(observed.status==200&&observed.body.path("status").asText().equals("CONFIRMED")
            &&observed.body.path("kind").asText().equals(kind)&&observed.body.path("blockHash").asText().equals(receipt.path("blockHash").asText()),"SERVER_CASH_RECEIPT_"+kind);
    }
    JsonNode nextMarketTick(String jwt)throws Exception{
        // Bounded read-only observation BEFORE creating any draft/claim/Report. No transaction retry.
        Instant before=Instant.parse(freshMarket(jwt).path("observedAt").asText());
        for(int n=0;n<40;n++){Thread.sleep(100);JsonNode current=freshMarket(jwt);if(Instant.parse(current.path("observedAt").asText()).isAfter(before))return current;}
        throw new IllegalStateException("C_DIRECT_DEMO_NEXT_PRODUCT_TICK_UNAVAILABLE");
    }
    JsonNode execute(String action,String position,String jwt)throws Exception{
        JsonNode market=nextMarketTick(jwt);BigInteger mark=new java.math.BigDecimal(market.path("price").asText()).movePointRight(8).toBigIntegerExact();
        BigInteger pmin=mark.multiply(BigInteger.valueOf(95)).divide(BigInteger.valueOf(100)),pmax=mark.multiply(BigInteger.valueOf(105)).divide(BigInteger.valueOf(100));
        BigInteger margin=CAnvilContractGate.units(1000),qmin,qmax;
        if(action.equals("OPEN")){qmin=FuturesMath.quantity(margin,10,pmax);qmax=FuturesMath.quantity(margin,10,pmin);}
        else{String p=g.call(g.rules,"getPosition(bytes32)",position);qmin=qmax=CAnvilContractGate.wordNum(p,3);}
        ObjectNode body=CAnvilContractGate.JSON.createObjectNode().put("action",action).put("side","LONG").put("leverage","10").put("collateralAfterE18",margin.toString()).put("priceMinE8",pmin.toString()).put("priceMaxE8",pmax.toString()).put("quantityMinE18",qmin.toString()).put("quantityMaxE18",qmax.toString());if(position!=null)body.put("positionId",position);
        var draft=http("POST","/api/c-futures/direct/intents",body,jwt);check(draft.status==200,"DRAFT_"+action);String id=draft.body.path("intentId").asText();
        var execution=http("POST","/api/c-futures/direct/intents/"+id+"/execution",null,jwt);check(execution.status==200,"EXECUTION_"+action);JsonNode e=execution.body;
        var tx=e.path("transaction");check(tx.path("from").asText().equals(g.addr("userA"))&&tx.path("to").asText().equals(g.controller)&&tx.path("value").asText().equals("0x0")&&tx.path("chainId").asText().equals("31337"),"DIRECT_TX_SCOPE");
        Call call=g.abi.decode(CAbiModels.Target.CONTROLLER,tx.path("data").asText());check(call instanceof Direct,"DIRECT_CALL_TYPE");Direct direct=(Direct)call;
        check(direct.intent().operationId().equals(id)&&direct.intent().positionId().equals(draft.body.path("intent").path("positionId").asText()),"EXACT_INTENT");
        g.crypto.verifyReport(direct.report(),identity.chainId(),identity.rules().address(),identity.oracle(),direct.priceSignature());
        JsonNode receipt=g.send(g.keys.get("userA"),g.controller,tx.path("data").asText(),true);
        var r=direct.report();var i=direct.intent();var m=FuturesMath.mark(FuturesMath.Side.LONG,r.quantity(),r.entryPriceE8(),r.collateralAfter(),r.priceE8());
        List<CAnvilContractGate.Expected> expected=new ArrayList<>();expected.add(new CAnvilContractGate.Expected(g.rules,EmitterKind.RULES,new OperationApplied(r.operationId(),r.positionId(),r.action(),r.expectedVersion().add(BigInteger.ONE),r.priceE8(),r.collateralAfter(),m.unrealized(),action.equals("CLOSE")?m.realized():BigInteger.ZERO,action.equals("CLOSE")?m.badDebt():BigInteger.ZERO,r.expectedCallEpoch(),BigInteger.ZERO)));
        expected.add(new CAnvilContractGate.Expected(g.rules,EmitterKind.RULES,new FuturesReportConsumed(r.operationId(),r.observedAt(),r.validUntil())));
        if(action.equals("OPEN"))expected.add(new CAnvilContractGate.Expected(g.vault,EmitterKind.VAULT,new CollateralLocked(r.operationId(),r.positionId(),i.owner(),r.collateralAfter(),r.collateralAfter())));
        else expected.add(new CAnvilContractGate.Expected(g.vault,EmitterKind.VAULT,new PositionSettled(r.operationId(),r.positionId(),i.owner(),r.collateralAfter(),m.realized(),m.badDebt(),m.realized().max(BigInteger.ZERO),BigInteger.ZERO)));
        expected.add(new CAnvilContractGate.Expected(g.controller,EmitterKind.CONTROLLER,new CUserIntentExecuted(r.operationId(),r.positionId(),i.owner(),g.crypto.intentDigest(i,identity.chainId(),g.controller),g.crypto.reportDigest(r,identity.chainId(),g.rules),r.action(),i.nonce(),true)));g.expect(receipt,expected);
        String hash=receipt.path("transactionHash").asText();var observed=http("GET","/api/c-futures/direct/transactions/"+hash+"?intentId="+id,null,jwt);check(observed.status==200&&observed.body.path("status").asText().equals("CONFIRMED"),"SERVER_CANONICAL_RECEIPT");
        g.save("user-"+action.toLowerCase(Locale.ROOT)+"-canonical.json",CAnvilContractGate.JSON.createObjectNode().put("intentId",id).put("positionId",i.positionId()).put("transactionHash",hash).put("blockHash",receipt.path("blockHash").asText()).put("owner",i.owner()).put("direct",true).put("realMetaMask",false));
        return e;
    }
    void databaseFinal()throws Exception{
        try(Connection c=ownerConnection();Statement s=c.createStatement()){
            try(ResultSet r=s.executeQuery("SELECT (SELECT count(*) FROM public.users),(SELECT count(*) FROM tms_c_identity.wallet_links),(SELECT count(*) FROM tms_c_execution.drafts),(SELECT count(*) FROM tms_c_execution.direct_execution_claims),(SELECT count(*) FROM tms_c_execution.direct_executions),(SELECT count(*) FROM public.c_direct_demo_sentinel WHERE value='PRESERVE_C_DIRECT_DEMO'),(SELECT step FROM public.synthetic_market_state WHERE symbol='mSEC')")){check(r.next()&&r.getInt(1)==2&&r.getInt(2)==2&&r.getInt(3)==2&&r.getInt(4)==2&&r.getInt(5)==2&&r.getInt(6)==1&&r.getLong(7)>1,"PG_FINAL_METADATA");}
        }
    }
    static byte[] join(byte[]...parts){var out=new java.io.ByteArrayOutputStream();for(var p:parts)out.writeBytes(p);return out.toByteArray();}
    static String secret(String key){String value=System.getenv(key);check(value!=null&&value.matches("[A-Za-z0-9._-]{8,128}"),"PRIVATE_ENV");return value;}
    static void check(boolean ok,String code){if(!ok)throw new IllegalStateException("C_DIRECT_DEMO_"+code);}
    static void selfTest(Path fixture)throws Exception{
        // Do not construct HttpClient/Selector during this Windows no-socket preparation.
        // Actual Linux bootstrap invokes the original helper's same check before any RPC.
        JsonNode sourceMap=CAnvilContractGate.JSON.readTree(Files.readAllBytes(fixture.resolve("source-map.json")));
        check(sourceMap.isArray()&&sourceMap.size()>0&&sourceMap.size()<256,"SELF_TEST_SOURCE_MAP");
        for(JsonNode item:sourceMap){
            String name=item.path("file").asText();check(name.matches("source-[0-9]{3}\\.bin"),"SELF_TEST_SOURCE_PATH");
            byte[] raw=Files.readAllBytes(fixture.resolve(name));check(raw.length<=512*1024,"SELF_TEST_SOURCE_SIZE");
            check(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)).equals(item.path("sha256").asText())
                &&CAnvilContractGate.compilerSourceMatches(raw,item.path("keccak256").asText()),"SELF_TEST_SOURCE_HASH");
        }
        for(String name:List.of("c-wallet-schema.sql","c-consent-schema.sql","c-direct-execution-schema.sql","c-direct-demo-synthetic-schema.sql")){
            byte[] file=Files.readAllBytes(fixture.resolve(name));check(file.length>0&&file.length<=65536,"SELF_TEST_SCHEMA");
        }
        check(CAnvilContractGate.raw(ECKeyPair.create(BigInteger.ONE),"0x"+"11".repeat(20),"0x",BigInteger.ZERO).length>0,"RAW_PRIMITIVE_AVAILABLE");
    }
}
