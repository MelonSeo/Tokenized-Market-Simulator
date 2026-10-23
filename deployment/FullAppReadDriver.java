import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.wallet.faucet.FaucetSchemaFixtureBridge;
import com.pricetrack.exchange.conditional.ConditionalSchemaFixtureBridge;
import com.pricetrack.exchange.custody.runtime.CCustodyIdentity;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

/** Fresh DB bootstrap and actual HTTP/STOMP reads only. Test wallet proofs never leave this actor. */
public final class FullAppReadDriver {
    static final ObjectMapper JSON=new ObjectMapper();static String stage="ARGS";
    static void check(boolean ok,String code){if(!ok)throw new IllegalStateException("FULL_READ_"+code);}
    static String secret(String key){String v=System.getenv(key);check(v!=null&&v.matches("[0-9a-f]{64}"),"SECRET");return v;}
    static DataSource source(String url,String user,String password){return new DataSource(){
        public Connection getConnection()throws SQLException{return DriverManager.getConnection(url,user,password);}
        public Connection getConnection(String u,String p)throws SQLException{throw new SQLFeatureNotSupportedException();}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter p){}public void setLoginTimeout(int n){}public int getLoginTimeout(){return 3;}
        public Logger getParentLogger(){return Logger.getLogger("FULL_READ");}public <T>T unwrap(Class<T> t)throws SQLException{throw new SQLException("NOT_WRAPPER");}public boolean isWrapperFor(Class<?> t){return false;}
    };}
    static void bootstrap(Properties manifest,Path fixture,Path config)throws Exception {
        String run=manifest.getProperty("runId"),suffix=run.substring(5),db="ccpg_"+suffix,owner="ccowner_"+suffix,api="ccapi_"+suffix;
        String url="jdbc:postgresql://pg:5432/"+db+"?sslmode=disable&connectTimeout=3&socketTimeout=8";
        var ds=source(url,owner,secret("FULL_READ_OWNER_PASSWORD"));stage="FRESH_OWNER";
        try(var c=ds.getConnection();var s=c.createStatement()){
            s.setQueryTimeout(3);
            try(var r=s.executeQuery("SELECT current_database(),current_user,(SELECT count(*) FROM information_schema.tables WHERE table_schema='public')")){check(r.next()&&db.equals(r.getString(1))&&owner.equals(r.getString(2))&&r.getInt(3)==0,"EMPTY_DATABASE");}
            s.execute("SET log_statement='none'; SET log_min_error_statement='panic'; SET log_parameter_max_length=0; SET log_parameter_max_length_on_error=0");
            stage="ORIGINAL_BOOTSTRAP";
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
            FaucetSchemaFixtureBridge.install(ds);ConditionalSchemaFixtureBridge.install(ds);
            new ResourceDatabasePopulator(new ClassPathResource("c-wallet-schema.sql")).execute(ds);
            s.execute("CREATE ROLE "+api+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '"+secret("FULL_READ_API_PASSWORD")+"'");
            s.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT CONNECT ON DATABASE "+db+" TO "+api+"; GRANT USAGE ON SCHEMA public,tms_c_identity TO "+api);
            s.execute("GRANT SELECT,INSERT,UPDATE(nickname) ON public.users TO "+api);
            s.execute("GRANT SELECT,INSERT ON public.user_balances TO "+api);
            s.execute("GRANT SELECT,INSERT,UPDATE ON public.market_candles,public.synthetic_market_state TO "+api);
            s.execute("GRANT USAGE,SELECT ON SEQUENCE public.users_id_seq,public.user_balances_id_seq,public.market_candles_id_seq TO "+api);
            s.execute("GRANT SELECT ON public.faucet_grants,public.faucet_policy_versions,public.faucet_account_usage,public.faucet_budget_usage,public.faucet_claims TO "+api);
            s.execute("GRANT SELECT,INSERT ON tms_c_identity.wallet_challenges,tms_c_identity.wallet_links TO "+api);
            s.execute("GRANT UPDATE(consumed,proof_signature) ON tms_c_identity.wallet_challenges TO "+api);
        }
        var apiSource=source(url,api,secret("FULL_READ_API_PASSWORD"));stage="API_VERIFY_MODE";
        FaucetSchemaFixtureBridge.verify(apiSource,"kst-v1");ConditionalSchemaFixtureBridge.verify(apiSource);
        try(var c=apiSource.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit,(SELECT count(*) FROM pg_auth_members WHERE member=pg_roles.oid),has_column_privilege(current_user,'public.users','nickname','UPDATE'),has_column_privilege(current_user,'public.users','role','UPDATE'),has_column_privilege(current_user,'public.users','id','UPDATE') FROM pg_roles WHERE rolname=current_user")){
            check(r.next()&&r.getBoolean(1)&&r.getInt(2)==0&&r.getBoolean(3)&&!r.getBoolean(4)&&!r.getBoolean(5),"API_ROLE_AND_ROWLOCK_COLUMN");
        }
        var identity=JSON.readValue(fixture.resolve("c-identity.json").toFile(),CCustodyIdentity.class);
        var p=new Properties();
        for(String flag:FullAppReadGate.OFF)p.setProperty(flag,"false");
        p.setProperty("app.c-futures.enabled","true");p.setProperty("app.c-futures.direct.enabled","true");
        p.setProperty("app.c-futures.chain-id",identity.chainId().toString());p.setProperty("app.c-futures.genesis-hash",identity.genesisHash());
        for(var e:Map.of("token",identity.token(),"controller",identity.controller(),"rules",identity.rules(),"vault",identity.vault()).entrySet()){
            p.setProperty("app.c-futures."+e.getKey(),e.getValue().address());p.setProperty("app.c-futures."+e.getKey()+"-code-hash",e.getValue().codeHash());
        }
        p.setProperty("app.c-futures.relayer",identity.relayer());p.setProperty("app.c-futures.oracle",identity.oracle());p.setProperty("app.c-futures.pause-admin",identity.pauseAdmin());
        p.setProperty("app.c-futures.symbol-hash",identity.symbolHash());p.setProperty("app.c-futures.policy-hash",identity.policyHash());p.setProperty("app.c-futures.origin",identity.origin());
        p.setProperty("app.c-futures.rpc-uri","http://anvil:8545");p.setProperty("app.c-futures.direct.oracle-token-file","/gate-config/denied-oracle-token");
        p.setProperty("spring.datasource.url",url);p.setProperty("spring.datasource.username",api);p.setProperty("spring.datasource.password",secret("FULL_READ_API_PASSWORD"));
        p.setProperty("app.jwt.secret",secret("FULL_READ_JWT_SECRET"));p.setProperty("app.jwt.validity-ms","300000");
        p.setProperty("spring.profiles.active","local-read-gate");p.setProperty("app.schema.startup-mode","verify-existing");p.setProperty("spring.sql.init.mode","never");p.setProperty("spring.jpa.hibernate.ddl-auto","none");
        p.setProperty("spring.autoconfigure.exclude","org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration");
        p.setProperty("spring.datasource.hikari.maximum-pool-size","4");p.setProperty("spring.datasource.hikari.minimum-idle","0");p.setProperty("spring.datasource.hikari.connection-timeout","3000");
        p.setProperty("app.price.provider","simulated");p.setProperty("app.price.simulation.bootstrap-days","0");p.setProperty("app.price.update-interval-ms","3000");p.setProperty("app.admin.password","");
        p.setProperty("app.websocket.allowed-origin-patterns",identity.origin());p.setProperty("server.port","8080");p.setProperty("logging.level.root","WARN");
        FullAppReadGate.verify(p);
        try(var out=Files.newOutputStream(config.resolve("application.properties"),StandardOpenOption.CREATE_NEW)){p.store(out,"new hidden read integration only");}
        Files.writeString(config.resolve("denied-oracle-token"),"0".repeat(64),StandardOpenOption.CREATE_NEW);
    }
    record Reply(int status,JsonNode body){}
    static Reply http(HttpClient client,String method,String path,String token,Object body,String origin)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://app:8080"+path)).timeout(Duration.ofSeconds(10)).header("Origin",origin);
        if(token!=null)request.header("Authorization","Bearer "+token);
        request.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var in=response.body()){byte[] bytes=in.readNBytes(65537);check(bytes.length<=65536,"HTTP_BODY_LIMIT");return new Reply(response.statusCode(),JSON.readTree(bytes));}
    }
    static void verify(Properties manifest,Path fixture,Path evidence)throws Exception {
        var identity=JSON.readValue(fixture.resolve("c-identity.json").toFile(),CCustodyIdentity.class);String origin=identity.origin();
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()){
            stage="COMMON_401";var naked=http(client,"GET","/api/c-futures/config",null,null,origin);
            check(naked.status==401&&naked.body.path("code").asText().equals("AUTHENTICATION_REQUIRED"),"COMMON_401");
            String[] tokens=new String[2];long[] ids=new long[2];String[] wallets=new String[2];
            for(int i=0;i<2;i++){
                stage="SIGNUP_LOGIN_"+i;String login="read_"+manifest.getProperty("runId").substring(5)+"_"+i,password=secret("FULL_READ_LOGIN_PASSWORD");
                check(http(client,"POST","/api/auth/signup",null,Map.of("loginId",login,"password",password,"nickname","trial"+i),origin).status==201,"SIGNUP");
                var loginReply=http(client,"POST","/api/auth/login",null,Map.of("loginId",login,"password",password),origin);check(loginReply.status==200,"LOGIN");
                tokens[i]=loginReply.body.path("accessToken").asText();check(!tokens[i].isBlank(),"JWT");
                var me=http(client,"GET","/api/me",tokens[i],null,origin);check(me.status==200&&me.body.path("role").asText().equals("USER"),"ME");ids[i]=me.body.path("id").asLong();
                var cfg=http(client,"GET","/api/c-futures/config",tokens[i],null,origin);
                check(cfg.status==200&&cfg.body.path("value").path("chainId").asText().equals(identity.chainId().toString())&&cfg.body.path("value").path("genesisHash").asText().equals(identity.genesisHash()),"C_CONFIG");
                stage="WALLET_PROOF_"+i;var key=Keys.createEcKeyPair();wallets[i]="0x"+Keys.getAddress(key);
                var challenge=http(client,"POST","/api/c-futures/wallet/challenges",tokens[i],Map.of("wallet",wallets[i]),origin);check(challenge.status==200,"CHALLENGE");
                var signed=Sign.signPrefixedMessage(challenge.body.path("message").asText().getBytes(StandardCharsets.UTF_8),key);
                String signature=Numeric.toHexString(signed.getR())+Numeric.toHexStringNoPrefix(signed.getS())+Numeric.toHexStringNoPrefix(signed.getV());
                check(http(client,"POST","/api/c-futures/wallet/link",tokens[i],Map.of("challengeId",challenge.body.path("id").asText(),"signature",signature),origin).status==200,"LINK");
                var link=http(client,"GET","/api/c-futures/wallet",tokens[i],null,origin);check(link.status==200&&link.body.path("wallet").asText().equals(wallets[i]),"OWN_LINK");
                var account=http(client,"GET","/api/c-futures/account",tokens[i],null,origin);check(account.status==200&&account.body.path("value").path("owner").asText().equals(wallets[i]),"OWN_ACCOUNT");
            }
            check(ids[0]>0&&ids[1]>0&&ids[0]!=ids[1]&&!wallets[0].equals(wallets[1]),"DISTINCT_FRESH_USERS");
            stage="SCOPE_AND_403";
            var other=http(client,"GET","/api/c-futures/account?wallet="+wallets[0]+"&userId="+ids[0],tokens[1],null,origin);
            check(other.status==200&&other.body.path("value").path("owner").asText().equals(wallets[1]),"PRINCIPAL_SCOPE_NOT_QUERY_ID");
            var admin=http(client,"GET","/api/admin/faucet",tokens[0],null,origin);check(admin.status==403&&admin.body.path("code").asText().equals("ACCESS_DENIED"),"COMMON_403");
            for(String path:List.of("/api/wallet/faucet","/api/c-futures/direct/intents","/api/orders/buy")){
                var denied=http(client,"POST",path,tokens[0],Map.of(),origin);
                check(denied.status==403&&denied.body.path("code").asText().equals("READ_GATE_DENIED"),"WRITE_DENIED");
            }
            stage="MARKET_READ";check(http(client,"GET","/api/markets/mSEC",null,null,origin).body.path("provider").asText().equals("SIMULATED"),"MARKET");
            check(http(client,"GET","/api/markets/mSEC/candles?interval=1m&count=1",null,null,origin).status==200,"CANDLES");
            check(http(client,"GET","/api/c-futures/direct/market",tokens[0],null,origin).status==200,"C_MARKET");
            stage="STOMP_READ";stomp(client,tokens[0],origin);
            stage="DB_NO_FINANCIAL_WRITES";
            String suffix=manifest.getProperty("runId").substring(5);
            var owner=source("jdbc:postgresql://pg:5432/ccpg_"+suffix+"?sslmode=disable&connectTimeout=3&socketTimeout=8","ccowner_"+suffix,secret("FULL_READ_OWNER_PASSWORD"));
            try(var c=owner.getConnection();var s=c.createStatement()){
                for(String table:List.of("orders","trades","faucet_grants"))try(var r=s.executeQuery("SELECT count(*) FROM "+table)){check(r.next()&&r.getLong(1)==0,"NO_ORDERS_OR_GRANTS");}
                try(var r=s.executeQuery("SELECT (SELECT count(*) FROM users),(SELECT count(*) FROM user_balances),(SELECT count(*) FROM user_balances WHERE amount<>0 OR locked_amount<>0),(SELECT count(*) FROM tms_c_identity.wallet_links)")){check(r.next()&&r.getInt(1)==2&&r.getInt(2)==4&&r.getInt(3)==0&&r.getInt(4)==2,"FRESH_USERS_ZERO_LEDGER");}
            }
            Files.write(evidence.resolve("verify.json"),JSON.writeValueAsBytes(Map.of("result","PASS","scope","ACTUAL_FULL_APP_HTTP_STOMP_AND_C_READ_ONLY_NOT_UI_OR_TRADING","freshUsers",2,"common401",true,"common403",true,"stompRead",true,"proofSignaturesDriverOnly",true,"runId",manifest.getProperty("runId"),"sourceIdentity",manifest.getProperty("sourceIdentity"))),StandardOpenOption.CREATE_NEW);
        }
    }
    static void stomp(HttpClient client,String token,String origin)throws Exception {
        var messages=new LinkedBlockingQueue<String>(32);var partial=new StringBuilder();
        var socket=client.newWebSocketBuilder().header("Origin",origin).connectTimeout(Duration.ofSeconds(3)).buildAsync(URI.create("ws://app:8080/ws"),new WebSocket.Listener(){
            public void onOpen(WebSocket ws){ws.request(1);}
            public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){partial.append(data);check(partial.length()<65536,"WS_LIMIT");if(last){messages.offer(partial.toString());partial.setLength(0);}ws.request(1);return null;}
        }).get(5,TimeUnit.SECONDS);
        try {
            socket.sendText("CONNECT\naccept-version:1.2\nhost:app\nAuthorization:Bearer "+token+"\nheart-beat:0,0\n\n\0",true).get(3,TimeUnit.SECONDS);
            check(Objects.toString(messages.poll(5,TimeUnit.SECONDS),"").contains("CONNECTED"),"STOMP_CONNECT");
            socket.sendText("SUBSCRIBE\nid:read-price\ndestination:/topic/markets/mSEC/price\n\n\0",true).get(3,TimeUnit.SECONDS);
            boolean price=false;long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while(System.nanoTime()<end){String frame=messages.poll(1,TimeUnit.SECONDS);if(frame!=null&&frame.contains("MESSAGE")&&frame.contains("SIMULATED")){price=true;break;}}
            check(price,"STOMP_PRICE");
        } finally {socket.sendClose(WebSocket.NORMAL_CLOSURE,"read complete").get(3,TimeUnit.SECONDS);}
    }
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("--self-test")){
            for(var method:FullAppReadDriver.class.getDeclaredMethods())if(!method.isSynthetic())for(var parameter:method.getParameters())check(parameter.isNamePresent(),"PARAMETERS");
            Path root=Path.of(args[1]);for(String name:List.of("schema.sql","faucet-schema.sql","conditional-schema.sql","c-wallet-schema.sql","application.yml")){
                byte[] original=Files.readAllBytes(root.resolve(name)),copy=Files.readAllBytes(root.resolve("classes").resolve(name));
                try(var in=FullAppReadDriver.class.getResourceAsStream("/"+name)){check(in!=null&&Arrays.equals(original,copy)&&Arrays.equals(original,in.readAllBytes()),"RESOURCE_CLASSLOADER");}
            }
            var type=Class.forName("com.pricetrack.exchange.ExchangeApplication",false,FullAppReadDriver.class.getClassLoader());
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(root.resolve("classes").toRealPath()),"ACTUAL_APPLICATION_CLASS_ORIGIN");
            int bound=0;
            for(String name:List.of("com.pricetrack.exchange.custody.direct.CDirectMarketController","com.pricetrack.exchange.custody.api.CCustodyController")){
                var controller=Class.forName(name,false,FullAppReadDriver.class.getClassLoader());
                check(Path.of(controller.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(root.resolve("classes").toRealPath()),"CONTROLLER_CLASS_ORIGIN");
                for(var method:controller.getDeclaredMethods())if(!method.isSynthetic())for(var parameter:method.getParameters()){
                    check(parameter.isNamePresent(),"CONTROLLER_PARAMETERS");bound++;
                }
            }
            check(bound>0,"BOUND_PARAMETERS_PRESENT");
            System.out.println("FULL_READ_DRIVER_NO_SOCKET_SELF_TEST_PASS");return;
        }
        check(args.length==4&&Set.of("bootstrap","verify").contains(args[0])&&args[1].equals("/fixture")&&args[2].equals("/gate-config")&&args[3].equals("/evidence"),"FIXED_PATHS");
        var p=new Properties();try(var in=Files.newInputStream(Path.of(args[1],"manifest.properties"))){p.load(in);}
        check(p.getProperty("runId","").matches("ccpg-[0-9a-f]{16}")&&"NON_USER_READ_INTEGRATION".equals(p.getProperty("scope")),"NEW_SCOPE");
        try{if(args[0].equals("bootstrap"))bootstrap(p,Path.of(args[1]),Path.of(args[2]));else verify(p,Path.of(args[1]),Path.of(args[3]));}
        catch(Exception failure){
            String state="";Throwable next=failure;for(int n=0;next!=null&&n<8;n++,next=next.getCause())if(next instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[A-Z0-9]{5}"))state=sql.getSQLState();
            Files.write(Path.of(args[3],"failure.json"),JSON.writeValueAsBytes(Map.of("stage",stage,"type",failure.getClass().getSimpleName(),"sqlState",state)),StandardOpenOption.CREATE_NEW);
            System.err.println("FULL_READ_DRIVER_STOP_NO_RETRY");System.exit(1);
        }
    }
}
