import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.wallet.faucet.FaucetSchemaFixtureBridge;
import com.pricetrack.exchange.conditional.ConditionalSchemaFixtureBridge;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.junit.jupiter.api.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.jupiter.api.Assertions.*;

/** New PG-only startup unit. No App, chain, users, funding, keys or consent tests. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public final class SchemaStartupPostgresTests {
    static final ObjectMapper JSON=new ObjectMapper();
    static final List<String> SQL=List.of("schema.sql","faucet-schema.sql","conditional-schema.sql");
    static final List<String> READ_TABLES=List.of("faucet_policy_versions","faucet_account_usage","faucet_budget_usage","faucet_claims","faucet_grants","user_balances","conditional_orders");
    static final List<String> DATA_TABLES=List.of("assets","users","user_balances","orders","faucet_grants","faucet_policy_versions","faucet_account_usage","faucet_budget_usage","faucet_claims","conditional_orders");
    static Path fixture,evidence;static String run,identity,ownerName,apiName,badName,baseline;
    static DataSource owner,api,bad;static final AtomicInteger selects=new AtomicInteger();
    static boolean bootstrapped;
    static volatile String stage="NOT_STARTED";
    static void require(boolean ok,String code){if(!ok)throw new IllegalStateException("SCHEMA_PG_"+code);}
    static String hash(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    static String secret(String key){String value=System.getenv(key);require(value!=null&&value.matches("[0-9a-f]{64}"),"SECRET_FORMAT");return value;}
    static void ownerSql(String sql)throws Exception{try(var c=owner.getConnection();var s=c.createStatement()){s.setQueryTimeout(3);s.execute(sql);}}
    static String denied(String sql)throws Exception{try(var c=api.getConnection();var s=c.createStatement()){s.setQueryTimeout(3);try{s.execute(sql);return "NO_ERROR";}catch(SQLException safe){return safe.getSQLState();}}}
    @BeforeAll static void bootstrap()throws Exception {
        stage="MANIFEST";
        require("true".equals(System.getenv("SCHEMA_STARTUP_PG_TESTS")),"EXPLICIT_OPT_IN");
        var p=new Properties();try(var in=Files.newInputStream(fixture.resolve("manifest.properties"))){p.load(in);}
        run=p.getProperty("runId","");identity=p.getProperty("sourceIdentity","");
        require(run.matches("ccpg-[0-9a-f]{16}")&&identity.matches("[0-9a-f]{64}")&&"SCHEMA_STARTUP_PG_ONLY".equals(p.getProperty("unitScope")),"IDENTITY");
        String suffix=run.substring(5),db="ccpg_"+suffix;ownerName="ccowner_"+suffix;apiName="ccapi_"+suffix;badName="ssbad_"+suffix;
        String url="jdbc:postgresql://pg:5432/"+db+"?sslmode=disable&connectTimeout=3&socketTimeout=8";
        require(db.equals(p.getProperty("database"))&&ownerName.equals(p.getProperty("databaseOwner"))&&apiName.equals(p.getProperty("apiUser"))&&url.equals(p.getProperty("jdbcUrl")),"DATABASE_SCOPE");
        owner=new DriverSource(url,ownerName,secret("SCHEMA_PG_OWNER_PASSWORD"));
        String apipw=secret("SCHEMA_PG_API_PASSWORD"),badpw=secret("SCHEMA_PG_BAD_PASSWORD");
        try(var c=owner.getConnection();var s=c.createStatement()) {
            stage="FRESH_OWNER";
            s.setQueryTimeout(3);
            try(var r=s.executeQuery("SELECT current_database(),current_user,(SELECT count(*) FROM information_schema.tables WHERE table_schema='public')")){require(r.next()&&db.equals(r.getString(1))&&ownerName.equals(r.getString(2))&&r.getInt(3)==0,"FRESH_OWNED_DB");}
            s.execute("SET log_statement='none'; SET log_min_error_statement='panic'; SET log_parameter_max_length=0; SET log_parameter_max_length_on_error=0");
            for(String name:SQL)require(hash(Files.readAllBytes(fixture.resolve(name))).equals(p.getProperty("sha."+name)),"SQL_IDENTITY");
            stage="ORIGINAL_INSTALL";new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(owner);
            FaucetSchemaFixtureBridge.install(owner);ConditionalSchemaFixtureBridge.install(owner);
            s.execute("CREATE TABLE public.schema_startup_identity(run_id text PRIMARY KEY,source_identity text NOT NULL)");
            try(var q=c.prepareStatement("INSERT INTO public.schema_startup_identity VALUES(?,?)")){q.setString(1,run);q.setString(2,identity);q.executeUpdate();}
            for(var entry:Map.of(apiName,apipw,badName,badpw).entrySet()) {
                s.execute("CREATE ROLE "+entry.getKey()+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '"+entry.getValue()+"'");
                s.execute("GRANT CONNECT ON DATABASE "+db+" TO "+entry.getKey()+"; GRANT USAGE ON SCHEMA public TO "+entry.getKey());
                s.execute("GRANT SELECT ON "+String.join(",",READ_TABLES)+" TO "+entry.getKey());
            }
            s.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        }
        api=new DriverSource(url,apiName,apipw);bad=new DriverSource(url,badName,badpw);
        stage="LEAST_PRIVILEGE";leastPrivilege(api,apiName);leastPrivilege(bad,badName);
        stage="BASELINE";
        baseline=fingerprint();bootstrapped=true;
    }
    static void leastPrivilege(DataSource source,String name)throws Exception {
        try(var c=source.getConnection();var s=c.createStatement()){
            try(var r=s.executeQuery("SELECT current_user,NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit AND NOT rolreplication AND NOT rolbypassrls,(SELECT count(*) FROM pg_auth_members WHERE member=pg_roles.oid),has_schema_privilege(current_user,'public','CREATE'),has_database_privilege(current_user,current_database(),'CREATE') FROM pg_roles WHERE rolname=current_user")){
                require(r.next()&&name.equals(r.getString(1))&&r.getBoolean(2)&&r.getLong(3)==0&&!r.getBoolean(4)&&!r.getBoolean(5),"LEAST_PRIVILEGE");
            }
            for(String table:READ_TABLES)try(var r=s.executeQuery("SELECT c.relowner<>(SELECT oid FROM pg_roles WHERE rolname=current_user),has_table_privilege(current_user,'public."+table+"','SELECT'),has_table_privilege(current_user,'public."+table+"','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') FROM pg_class c WHERE c.oid='public."+table+"'::regclass")){
                require(r.next()&&r.getBoolean(1)&&r.getBoolean(2)&&!r.getBoolean(3),"TABLE_PRIVILEGE");
            }
        }
    }
    @Test @Order(1) void actualApiVerificationIsSelectOnlyAndPreservesOwnerInstallation()throws Exception {
        stage="API_VERIFY_ONLY";
        var observed=observed(api);FaucetSchemaFixtureBridge.verify(observed,"kst-v1");ConditionalSchemaFixtureBridge.verify(observed);
        assertTrue(selects.get()>0);assertEquals(baseline,fingerprint());leastPrivilege(api,apiName);
    }
    @Test @Order(2) void apiCannotCreateInsertOrDisableProtection()throws Exception {
        stage="API_DDL_DENIED";
        assertEquals("42501",denied("CREATE TABLE public.not_allowed(id int)"));
        assertEquals("42501",denied("INSERT INTO public.faucet_account_usage(user_id,grant_count) VALUES(999,0)"));
        assertEquals("42501",denied("ALTER TABLE public.faucet_grants DISABLE TRIGGER faucet_grants_policy_immutable"));
    }
    @Test @Order(3) void separateWrongOwnerPrincipalIsRejectedWithoutPromotingRuntimeApi()throws Exception {
        stage="WRONG_OWNER";
        try {ownerSql("ALTER TABLE public.faucet_grants OWNER TO "+badName);
            var error=assertThrows(IllegalStateException.class,()->FaucetSchemaFixtureBridge.verify(observed(bad),"kst-v1"));
            assertEquals("PREINSTALLED_SCHEMA_MISSING_OR_CONFLICTING",error.getMessage());
        } finally {ownerSql("ALTER TABLE public.faucet_grants OWNER TO "+ownerName);}
    }
    @Test @Order(4) void disabledTriggerIsRejected()throws Exception {
        stage="DISABLED_TRIGGER";
        try {ownerSql("ALTER TABLE public.faucet_grants DISABLE TRIGGER faucet_grants_policy_immutable");
            var error=assertThrows(IllegalStateException.class,()->FaucetSchemaFixtureBridge.verify(observed(api),"kst-v1"));
            assertEquals("PREINSTALLED_SCHEMA_MISSING_OR_CONFLICTING",error.getMessage());
        } finally {ownerSql("ALTER TABLE public.faucet_grants ENABLE TRIGGER faucet_grants_policy_immutable");}
    }
    @Test @Order(5) void changedFunctionBodyIsRejectedAndRestoredByActualInstall()throws Exception {
        stage="WRONG_BODY";
        try {ownerSql("CREATE OR REPLACE FUNCTION public.reject_faucet_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$");
            var error=assertThrows(IllegalStateException.class,()->FaucetSchemaFixtureBridge.verify(observed(api),"kst-v1"));
            assertEquals("PREINSTALLED_SCHEMA_MISSING_OR_CONFLICTING",error.getMessage());
        } finally {FaucetSchemaFixtureBridge.install(owner);}
    }
    @Test @Order(6) void nonActiveConfiguredVersionCannotActivateOrModifyPolicyRows()throws Exception {
        stage="WRONG_VERSION";
        var error=assertThrows(IllegalStateException.class,()->FaucetSchemaFixtureBridge.verify(observed(api),"non-active-policy"));
        assertEquals("PREINSTALLED_FAUCET_NOT_READY",error.getMessage());
    }
    @AfterEach void restoredAndUnchanged()throws Exception {if(bootstrapped){assertEquals(baseline,fingerprint());leastPrivilege(api,apiName);FaucetSchemaFixtureBridge.verify(observed(api),"kst-v1");ConditionalSchemaFixtureBridge.verify(observed(api));}}
    static String fingerprint()throws Exception {
        var lines=new ArrayList<String>();
        try(var c=owner.getConnection();var s=c.createStatement()) {
            s.setQueryTimeout(3);
            for(String table:DATA_TABLES){lines.add(table);try(var r=s.executeQuery("SELECT to_jsonb(t)::text FROM public."+table+" t ORDER BY to_jsonb(t)::text")){while(r.next())lines.add(r.getString(1));}}
            try(var r=s.executeQuery("SELECT run_id,source_identity FROM public.schema_startup_identity")){require(r.next()&&run.equals(r.getString(1))&&identity.equals(r.getString(2))&&!r.next(),"MARKER");}
            try(var r=s.executeQuery("SELECT t.relname,r.rolname FROM pg_class t JOIN pg_roles r ON r.oid=t.relowner JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='public' AND t.relkind='r' ORDER BY t.relname")){while(r.next())lines.add(r.getString(1)+":"+r.getString(2));}
        }
        return hash(String.join("\n",lines).getBytes(StandardCharsets.UTF_8));
    }
    static DataSource observed(DataSource source) {
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            Object value=invoke(m,source,a);if(!m.getName().equals("getConnection"))return value;Connection connection=(Connection)value;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(q,n,b)->{
                if(n.getName().equals("prepareStatement")&&b!=null&&b[0] instanceof String sql){require(sql.stripLeading().startsWith("SELECT"),"NONSELECT_API");selects.incrementAndGet();}
                Object child=invoke(n,connection,b);
                if(n.getName().equals("createStatement"))return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(r,k,d)->{
                    if(k.getName().startsWith("execute")&&d!=null&&d[0] instanceof String sql){require(sql.stripLeading().startsWith("SELECT"),"NONSELECT_API");selects.incrementAndGet();}
                    return invoke(k,child,d);});
                return child;
            });
        });
    }
    static Object invoke(Method method,Object target,Object[] args)throws Throwable {try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}}
    static final class DriverSource implements DataSource {
        final String url,user,password;DriverSource(String url,String user,String password){this.url=url;this.user=user;this.password=password;}
        public Connection getConnection()throws SQLException{return DriverManager.getConnection(url,user,password);}
        public Connection getConnection(String u,String p)throws SQLException{throw new SQLFeatureNotSupportedException();}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter out){}public void setLoginTimeout(int value){}public int getLoginTimeout(){return 3;}
        public Logger getParentLogger(){return Logger.getLogger("SCHEMA_PG");}public <T>T unwrap(Class<T> c)throws SQLException{throw new SQLException("NOT_WRAPPER");}public boolean isWrapperFor(Class<?> c){return false;}
    }
    static Map<String,String> selfTest(Path root)throws Exception {
        var checked=new TreeMap<String,String>();
        for(String name:SQL){byte[] original=Files.readAllBytes(root.resolve(name)),copy=Files.readAllBytes(root.resolve("classes").resolve(name));
            try(var in=SchemaStartupPostgresTests.class.getResourceAsStream("/"+name)){require(in!=null&&Arrays.equals(original,copy)&&Arrays.equals(original,in.readAllBytes()),"RESOURCE_IDENTITY");}
            checked.put(name,hash(original));
        }
        for(Class<?> type:List.of(FaucetSchemaFixtureBridge.class,ConditionalSchemaFixtureBridge.class))
            for(var method:type.getDeclaredMethods())for(var parameter:method.getParameters())require(parameter.isNamePresent(),"PARAMETER_METADATA");
        Path classes=root.resolve("classes").toRealPath();var loader=SchemaStartupPostgresTests.class.getClassLoader();
        for(String name:List.of("com.pricetrack.exchange.common.schema.SchemaStartupMode","com.pricetrack.exchange.common.schema.PreinstalledSchemaVerifier","com.pricetrack.exchange.wallet.faucet.FaucetStore","com.pricetrack.exchange.wallet.faucet.FaucetPolicy","com.pricetrack.exchange.wallet.faucet.FaucetFailure","com.pricetrack.exchange.conditional.ConditionalSchema")){
            var type=Class.forName(name,false,loader);
            require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(classes),"CLASS_SHADOWING");
        }
        checked.put("scope","RESOURCE_METADATA_ONLY_NO_JDBC_ENV_OR_SOCKET");return checked;
    }
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("--self-test")){System.out.println(JSON.writeValueAsString(selfTest(Path.of(args[1]))));return;}
        require(args.length==2&&args[0].equals("/fixture")&&args[1].equals("/evidence"),"FIXED_PATHS");
        fixture=Path.of(args[0]);evidence=Path.of(args[1]);
        var request=LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(SchemaStartupPostgresTests.class)).build();
        var listener=new SummaryGeneratingListener();var boundary=new AtomicReference<String>("");
        var firstFailure=new org.junit.platform.launcher.TestExecutionListener(){
            @Override public void executionFinished(org.junit.platform.launcher.TestIdentifier id,org.junit.platform.engine.TestExecutionResult result){
                if(result.getStatus()==org.junit.platform.engine.TestExecutionResult.Status.FAILED)boundary.compareAndSet("",stage);
            }
        };
        LauncherFactory.create().execute(request,listener,firstFailure);var s=listener.getSummary();
        boolean pass=s.getTestsFoundCount()==6&&s.getTestsStartedCount()==6&&s.getTestsSucceededCount()==6&&s.getTotalFailureCount()==0&&s.getTestsSkippedCount()==0&&s.getTestsAbortedCount()==0;
        var result=new LinkedHashMap<String,Object>();result.put("unitScope","SCHEMA_STARTUP_PG_ONLY");result.put("result",pass?"PASS":"FAIL");result.put("runId",run);result.put("sourceIdentity",identity);
        result.put("testsFound",s.getTestsFoundCount());result.put("testsStarted",s.getTestsStartedCount());result.put("testsSucceeded",s.getTestsSucceededCount());result.put("testsFailed",s.getTestsFailedCount());result.put("containersFailed",s.getContainersFailedCount());result.put("failureCount",s.getTotalFailureCount());result.put("firstFailureBoundary",boundary.get());result.put("testsSkipped",s.getTestsSkippedCount());result.put("testsAborted",s.getTestsAbortedCount());result.put("apiSelectStatements",selects.get());
        var failures=new ArrayList<Map<String,String>>();for(var failure:s.getFailures()){String state="",code="";Throwable t=failure.getException();
            for(int n=0;t!=null&&n<8;n++,t=t.getCause()){if(t instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[A-Z0-9]{5}"))state=sql.getSQLState();if(!(t instanceof SQLException)&&t.getMessage()!=null&&t.getMessage().matches("(SCHEMA_PG|PREINSTALLED)_[A-Z0-9_]+"))code=t.getMessage();}
            failures.add(Map.of("test",failure.getTestIdentifier().getDisplayName(),"type",failure.getException().getClass().getSimpleName(),"sqlState",state,"safeCode",code));}
        result.put("failures",failures);result.put("scope","ACTUAL_NEW_PG_STARTUP_ONLY_NOT_APP_CHAIN_OR_CUSTODY_GATE");
        Files.writeString(evidence.resolve("result.json"),JSON.writeValueAsString(result),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        System.out.println(JSON.writeValueAsString(result));System.exit(pass?0:1);
    }
}
