import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.authorization.*;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.custody.runtime.*;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import static org.junit.jupiter.api.Assertions.*;

/** Actual new PG consent metadata only. No server, RPC, funds, real wallet, or execution claim. */
public final class CConsentPostgresTests {
    static final Instant NOW=Instant.parse("2026-10-23T12:00:00Z");
    static final String NS="tms_c_execution.";
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static Properties manifest;static Path fixture,evidence;
    static DataSource owner,api;static CConsentStore store;static CCustodyIdentity identity;
    static String run,sourceIdentity,ownerName,apiName,marker;

    @BeforeAll static void bootstrap()throws Exception {
        check("true".equals(System.getenv("C_CONSENT_PG_TESTS")),"EXPLICIT_OPT_IN");
        manifest=new Properties();try(var in=Files.newInputStream(fixture.resolve("manifest.properties"))){manifest.load(in);}
        run=manifest.getProperty("runId","");sourceIdentity=manifest.getProperty("sourceIdentity","");marker=manifest.getProperty("marker","");
        check(run.matches("ccpg-[0-9a-f]{16}")&&sourceIdentity.matches("[0-9a-f]{64}")&&marker.matches("[0-9a-f]{64}")&&"C_CONSENT_PG_ONLY".equals(manifest.getProperty("scope")),"MANIFEST_SCOPE");
        String suffix=run.substring(5),db="ccpg_"+suffix;ownerName="ccowner_"+suffix;apiName="ccapi_"+suffix;
        String url="jdbc:postgresql://pg:5432/"+db+"?sslmode=disable&connectTimeout=3&socketTimeout=8";
        check(db.equals(manifest.getProperty("database"))&&ownerName.equals(manifest.getProperty("databaseOwner"))&&apiName.equals(manifest.getProperty("apiUser"))&&url.equals(manifest.getProperty("jdbcUrl")),"DATABASE_IDENTITY");
        String ownerPassword=secret("C_CONSENT_PG_OWNER_PASSWORD"),apiPassword=secret("C_CONSENT_PG_API_PASSWORD");owner=new DriverSource(url,ownerName,ownerPassword);
        try(Connection c=owner.getConnection();Statement s=c.createStatement()){
            s.setQueryTimeout(3);try(ResultSet r=s.executeQuery("SELECT current_database(),current_user")){check(r.next()&&db.equals(r.getString(1))&&ownerName.equals(r.getString(2))&&!r.next(),"DATABASE_OWNER");}
            try(ResultSet r=s.executeQuery("SELECT count(*)>0 AND bool_and(auth_method='scram-sha-256') FROM pg_hba_file_rules WHERE type IN ('host','hostssl','hostnossl')")){check(r.next()&&r.getBoolean(1),"HOST_SCRAM");}
            try(ResultSet r=s.executeQuery("SELECT rolpassword LIKE 'SCRAM-SHA-256$%' FROM pg_authid WHERE rolname=current_user")){check(r.next()&&r.getBoolean(1),"OWNER_SCRAM");}
            try(ResultSet r=s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema IN ('public','tms_c_identity','tms_c_execution')")){check(r.next()&&r.getInt(1)==0,"EMPTY_NEW_DATABASE");}
            // New owner session only. Suppress statements/parameters before secret-bearing role DDL.
            s.execute("SET log_statement='none'; SET log_min_error_statement='panic'; SET log_parameter_max_length=0; SET log_parameter_max_length_on_error=0");
            s.execute("CREATE TABLE public.c_consent_fixture_identity(scope text NOT NULL,run_id text NOT NULL,marker text NOT NULL); CREATE TABLE public.c_consent_sentinel(value text PRIMARY KEY); INSERT INTO public.c_consent_sentinel VALUES('preserve'); CREATE TABLE public.users(id BIGINT PRIMARY KEY)");
            try(PreparedStatement p=c.prepareStatement("INSERT INTO public.c_consent_fixture_identity VALUES('C_CONSENT_PG_ONLY',?,?)")){p.setString(1,run);p.setString(2,marker);p.executeUpdate();}
            for(int n=1;n<=8;n++)s.executeUpdate("INSERT INTO public.users VALUES("+n+")");
            s.execute(schema("c-wallet-schema.sql","walletSchemaSha256"));
        }
        identity=deployment();
        for(int n=1;n<=8;n++)seedLink(n);
        schema("c-consent-schema.sql","consentSchemaSha256");
        new CConsentStore(owner,identity,CLOCK).installSchema();
        // Runtime read/write role has no owner fallback and no UPDATE/DELETE/TRUNCATE of evidence.
        try(Connection c=owner.getConnection();Statement s=c.createStatement()){
            s.execute("SET log_statement='none'; SET log_min_error_statement='panic'; SET log_parameter_max_length=0; SET log_parameter_max_length_on_error=0");
            s.execute("CREATE ROLE "+apiName+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '"+apiPassword+"'");
            s.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public,tms_c_identity,tms_c_execution TO "+apiName);
            s.execute("GRANT SELECT,UPDATE(id) ON public.users TO "+apiName+"; GRANT SELECT ON tms_c_identity.wallet_links TO "+apiName+"; GRANT SELECT,INSERT ON tms_c_execution.drafts,tms_c_execution.consents TO "+apiName);
            s.execute("CREATE FUNCTION public.c_consent_users_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'C_FIXTURE_USERS_IMMUTABLE' USING ERRCODE='55000'; END $$; CREATE TRIGGER c_users_immutable BEFORE UPDATE OR DELETE ON public.users FOR EACH ROW EXECUTE FUNCTION public.c_consent_users_immutable()");
            try(ResultSet r=s.executeQuery("SELECT rolpassword LIKE 'SCRAM-SHA-256$%' FROM pg_authid WHERE rolname='"+apiName+"'")){check(r.next()&&r.getBoolean(1),"API_SCRAM");}
        }
        api=new DriverSource(url,apiName,apiPassword);store=new CConsentStore(api,identity,CLOCK);
        check(schema("c-consent-schema.sql","consentSchemaSha256").equals(resource("c-consent-schema.sql")),"CONSENT_RESOURCE_EQUAL");
        try(Connection c=api.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT current_user,(SELECT rolsuper FROM pg_roles WHERE rolname=current_user),current_setting('log_parameter_max_length_on_error')")){check(r.next()&&apiName.equals(r.getString(1))&&!r.getBoolean(2)&&"0".equals(r.getString(3)),"API_ROLE");}
        try{new DriverSource(url,apiName,"wrong-"+apiPassword).getConnection().close();throw new AssertionError("C_PG_BAD_PASSWORD_ACCEPTED");}catch(SQLException expected){assertEquals("28P01",expected.getSQLState());}
    }
    @Test void freshInstallIdentityLeastPrivilegeAndSentinel()throws Exception {
        assertEquals("C_CONSENT_SCHEMA_ALREADY_EXISTS",assertThrows(CCustodyFailure.class,()->new CConsentStore(owner,identity,CLOCK).installSchema()).code());
        assertEquals("42501",state(api,"CREATE TABLE public.not_allowed(id int)"));
        assertEquals("55000",state(api,"UPDATE public.users SET id=id WHERE id=1"));
        assertEquals("42501",state(api,"TRUNCATE tms_c_execution.drafts CASCADE"));preserved();
    }
    @Test void exactKeyUserScopeAndDatabaseConstraints()throws Exception {
        Intent i=intent(1,101);var d=store.createDraft(1,"pg-idempotency-1",i);assertEquals(d,store.createDraft(1,"pg-idempotency-1",i));
        assertEquals("C_CONSENT_IDEMPOTENCY_CONFLICT",assertThrows(CCustodyFailure.class,()->store.createDraft(1,"pg-idempotency-1",intent(1,102))).code());
        assertEquals(404,assertThrows(CCustodyFailure.class,()->store.findDraft(2,i.operationId())).status());
        var crypto=new CEip712Verifier();for(String invalid:List.of(sign(i,2),signDigest(crypto.intentDigest(i,BigInteger.ONE,identity.controller().address()),1),signDigest(crypto.intentDigest(i,identity.chainId(),addr(88)),1)))
            assertEquals("C_CONSENT_INVALID_SIGNATURE",assertThrows(CCustodyFailure.class,()->store.acceptConsent(1,i.operationId(),d.intentDigest(),invalid)).code());
        assertEquals(409,assertThrows(CCustodyFailure.class,()->store.createDraft(1,"pg-different-key",i)).status());
        assertEquals("23503",state(api,"INSERT INTO tms_c_execution.drafts SELECT deployment_id,'"+hash(900)+"',999,owner,'"+"a".repeat(64)+"',request_hash,intent_digest,intent_json,created_at FROM tms_c_execution.drafts WHERE operation_id='"+i.operationId()+"'"));
        assertEquals(1,count("drafts",i.operationId()));preserved();
    }
    @Test void appendOnlyTriggersAndRoleDenialsProtectBothTables()throws Exception {
        Intent i=intent(3,103);var d=store.createDraft(3,"pg-append-only",i);store.acceptConsent(3,i.operationId(),d.intentDigest(),sign(i,3));
        for(String table:List.of("drafts","consents")){
            String filter=" WHERE operation_id='"+i.operationId()+"'";
            assertEquals("55000",state(owner,"UPDATE "+NS+table+" SET owner=owner"+filter));assertEquals("55000",state(owner,"DELETE FROM "+NS+table+filter));
            assertEquals("42501",state(api,"UPDATE "+NS+table+" SET owner=owner"+filter));assertEquals("42501",state(api,"DELETE FROM "+NS+table+filter));
            assertEquals(1,count(table,i.operationId()));
        }preserved();
    }
    @Test void twoConnectionsProduceOneDraftAndOneImmutableConsent()throws Exception {
        Intent i=intent(2,104);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{List<Future<CConsentModels.Consent>> futures=new ArrayList<>();for(int k=0;k<2;k++)futures.add(pool.submit(()->{ready.countDown();start.await();CConsentStore independent=new CConsentStore(api,identity,CLOCK);var d=independent.createDraft(2,"pg-two-workers",i);return independent.acceptConsent(2,i.operationId(),d.intentDigest(),sign(i,2));}));assertTrue(ready.await(3,TimeUnit.SECONDS));start.countDown();assertEquals(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
        assertEquals(1,count("drafts",i.operationId()));assertEquals(1,count("consents",i.operationId()));preserved();
    }
    @Test void failedConsentInsertRollsBackAndPreservesOriginalDraft()throws Exception {
        Intent i=intent(4,105);var d=store.createDraft(4,"pg-insert-failure",i);
        sql(owner,"CREATE FUNCTION tms_c_execution.c_fixture_fail_consent() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_id='"+i.operationId()+"' THEN RAISE EXCEPTION 'C_FIXTURE_INJECTED' USING ERRCODE='40001'; END IF; RETURN NEW; END $$; CREATE TRIGGER c_fixture_fail_consent AFTER INSERT ON tms_c_execution.consents FOR EACH ROW EXECUTE FUNCTION tms_c_execution.c_fixture_fail_consent()");
        assertEquals("C_CONSENT_STORAGE",assertThrows(CCustodyFailure.class,()->store.acceptConsent(4,i.operationId(),d.intentDigest(),sign(i,4))).code());
        assertEquals(0,count("consents",i.operationId()));assertEquals(d,store.findDraft(4,i.operationId()));preserved();
    }
    @Test void commitUncertaintyUsesObservationWithoutAutomaticRetry()throws Exception {
        Intent after=intent(5,106),before=intent(6,107);
        var committed=new CConsentStore(commitFault(api,true),identity,CLOCK);assertEquals("C_CONSENT_COMMIT_UNKNOWN",assertThrows(CCustodyFailure.class,()->committed.createDraft(5,"pg-commit-after",after)).code());
        assertTrue(store.findByKey(5,"pg-commit-after").isPresent());assertEquals(1,count("drafts",after.operationId()));
        var absent=new CConsentStore(commitFault(api,false),identity,CLOCK);assertEquals("C_CONSENT_COMMIT_UNKNOWN",assertThrows(CCustodyFailure.class,()->absent.createDraft(6,"pg-commit-before",before)).code());
        assertTrue(store.findByKey(6,"pg-commit-before").isEmpty());assertEquals(0,count("drafts",before.operationId()));preserved();
    }
    @AfterAll static void finalPreservation()throws Exception{if(owner!=null)preserved();}
    static void preserved()throws SQLException{try(Connection c=owner.getConnection();Statement s=c.createStatement()){
        try(ResultSet r=s.executeQuery("SELECT scope,run_id,marker FROM public.c_consent_fixture_identity")){check(r.next()&&"C_CONSENT_PG_ONLY".equals(r.getString(1))&&run.equals(r.getString(2))&&marker.equals(r.getString(3))&&!r.next(),"SENTINEL_IDENTITY");}
        try(ResultSet r=s.executeQuery("SELECT value FROM public.c_consent_sentinel")){check(r.next()&&"preserve".equals(r.getString(1))&&!r.next(),"SENTINEL_VALUE");}
        try(ResultSet r=s.executeQuery("SELECT count(*) FROM public.users")){check(r.next()&&r.getInt(1)==8,"USERS_PRESERVED");}
        try(ResultSet r=s.executeQuery("SELECT count(*) FROM tms_c_identity.wallet_links")){check(r.next()&&r.getInt(1)==8,"LINKS_PRESERVED");}
    }}
    static int count(String table,String op)throws SQLException{try(Connection c=owner.getConnection();PreparedStatement s=c.prepareStatement("SELECT count(*) FROM "+NS+table+" WHERE operation_id=?")){s.setString(1,op);try(ResultSet r=s.executeQuery()){r.next();return r.getInt(1);}}}
    static String state(DataSource ds,String sql)throws SQLException{try(Connection c=ds.getConnection();Statement s=c.createStatement()){s.setQueryTimeout(3);try{s.execute(sql);return "NO_ERROR";}catch(SQLException e){return e.getSQLState();}}}
    static void sql(DataSource ds,String sql)throws SQLException{try(Connection c=ds.getConnection();Statement s=c.createStatement()){s.setQueryTimeout(3);s.execute(sql);}}
    static void seedLink(int user)throws SQLException{
        try(Connection c=owner.getConnection()){
            String id=UUID.randomUUID().toString();
            try(PreparedStatement s=c.prepareStatement("INSERT INTO tms_c_identity.wallet_challenges VALUES(?,?,?,?,?,?,?,?,?,?,TRUE,NULL)")){
                s.setString(1,id);s.setString(2,identity.deploymentId());s.setInt(3,user);s.setString(4,wallet(user));s.setString(5,identity.origin());s.setString(6,"CONTROLLED_PG_LINK_FIXTURE_NOT_EIP191_ACCEPTANCE");s.setString(7,marker);s.setString(8,String.format("%064x",user));s.setTimestamp(9,Timestamp.from(NOW));s.setTimestamp(10,Timestamp.from(NOW.plusSeconds(120)));s.executeUpdate();}
            try(PreparedStatement s=c.prepareStatement("INSERT INTO tms_c_identity.wallet_links VALUES(?,?,?,?,?,?)")){s.setString(1,identity.deploymentId());s.setInt(2,user);s.setString(3,wallet(user));s.setString(4,id);s.setString(5,marker);s.setTimestamp(6,Timestamp.from(NOW));s.executeUpdate();}
        }
    }
    static String schema(String name,String property)throws Exception{byte[] bytes=Files.readAllBytes(fixture.resolve(name));check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(manifest.getProperty(property)),"SCHEMA_HASH");String sql=new String(bytes,StandardCharsets.UTF_8);check(sql.equals(resource(name)),"RESOURCE_HASH");return sql;}
    static String resource(String name)throws Exception{try(var in=CConsentPostgresTests.class.getResourceAsStream("/"+name)){check(in!=null,"RESOURCE_MISSING");return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    static String secret(String name){String value=System.getenv(name);check(value!=null&&value.matches("[0-9a-f]{64}"),"PRIVATE_SECRET_MISSING");return value;}
    static ECKeyPair key(int user){return ECKeyPair.create(BigInteger.valueOf(12300L+user));}
    static String wallet(int user){return "0x"+Keys.getAddress(key(user));}
    static String addr(int n){return "0x"+String.format("%040x",n);}static String hash(int n){return "0x"+String.format("%064x",n);}
    static CCustodyIdentity deployment(){return new CCustodyIdentity(BigInteger.valueOf(31337),hash(9),new CCustodyIdentity.Contract(addr(1),hash(1)),new CCustodyIdentity.Contract(addr(2),hash(2)),new CCustodyIdentity.Contract(addr(3),hash(3)),new CCustodyIdentity.Contract(addr(4),hash(4)),addr(5),addr(6),addr(7),hash(10),hash(11),"http://localhost:5173");}
    static Intent intent(int uid,int operation){return new Intent(wallet(uid),identity.relayer(),hash(operation),hash(operation+1000),CConsentModels.accountRef(wallet(uid),identity.symbolHash()),identity.symbolHash(),BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.TEN,BigInteger.ZERO,BigInteger.ZERO,BigInteger.valueOf(1000),BigInteger.valueOf(90),BigInteger.valueOf(110),BigInteger.ONE,BigInteger.valueOf(200),BigInteger.ZERO,BigInteger.valueOf(NOW.getEpochSecond()),BigInteger.valueOf(NOW.plusSeconds(120).getEpochSecond()),identity.policyHash());}
    static String sign(Intent intent,int uid){return signDigest(new CEip712Verifier().intentDigest(intent,identity.chainId(),identity.controller().address()),uid);}
    static String signDigest(String digest,int uid){var data=Sign.signMessage(Numeric.hexStringToByteArray(digest),key(uid),false);return Numeric.toHexString(data.getR())+Numeric.cleanHexPrefix(Numeric.toHexString(data.getS()))+Numeric.cleanHexPrefix(Numeric.toHexString(data.getV()));}
    static DataSource commitFault(DataSource ds,boolean after){var fired=new AtomicBoolean();return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{Object v=invoke(m,ds,a);if(!m.getName().equals("getConnection"))return v;Connection c=(Connection)v;return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p2,m2,a2)->{if(m2.getName().equals("commit")&&fired.compareAndSet(false,true)){if(after)c.commit();throw new SQLException("C_FIXTURE_AMBIGUOUS_COMMIT","08006");}return invoke(m2,c,a2);});});}
    static Object invoke(Method m,Object target,Object[] args)throws Throwable{try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    static void check(boolean condition,String code){if(!condition)throw new IllegalStateException("C_CONSENT_PG_"+code);}
    static final class DriverSource implements DataSource {
        final String url,user,password;DriverSource(String u,String n,String p){url=u;user=n;password=p;}
        public Connection getConnection()throws SQLException{return DriverManager.getConnection(url,user,password);}public Connection getConnection(String u,String p)throws SQLException{throw new SQLFeatureNotSupportedException();}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter out){}public void setLoginTimeout(int t){}public int getLoginTimeout(){return 3;}public Logger getParentLogger(){return Logger.getLogger("C_CONSENT_PG_TEST");}public <T>T unwrap(Class<T> c)throws SQLException{throw new SQLException("C_NOT_WRAPPER");}public boolean isWrapperFor(Class<?> c){return false;}
    }
    public static void main(String[] args)throws Exception{
        if(args.length==2&&"--resource-self-test".equals(args[0])){
            // Offline prepare only: no environment reads, JDBC, Spring, Docker, keys or sockets.
            Path root=Path.of(args[1]).toAbsolutePath().normalize();var checked=new LinkedHashMap<String,String>();
            checked.put("result","RESOURCE_SELF_TEST_PASS");
            for(String name:List.of("c-wallet-schema.sql","c-consent-schema.sql")){
                byte[] original=Files.readAllBytes(root.resolve(name)),copy=Files.readAllBytes(root.resolve("classes").resolve(name));
                check(Arrays.equals(original,copy)&&new String(original,StandardCharsets.UTF_8).equals(resource(name)),"RESOURCE_COPY_MISMATCH");
                checked.put(name.equals("c-wallet-schema.sql")?"walletSchemaSha256":"consentSchemaSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
            }
            checked.put("scope","CLASSPATH_SQL_RESOURCES_ONLY_NO_JDBC_SOCKET_OR_ENV");
            System.out.println(new ObjectMapper().writeValueAsString(checked));return;
        }
        check(args.length==2&&"/fixture".equals(args[0])&&"/evidence".equals(args[1]),"PATH_SCOPE");fixture=Path.of(args[0]);evidence=Path.of(args[1]);
        var request=LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(CConsentPostgresTests.class)).build();var listener=new SummaryGeneratingListener();LauncherFactory.create().execute(request,listener);var s=listener.getSummary();
        boolean pass=s.getTestsFoundCount()==6&&s.getTestsSucceededCount()==6&&s.getTotalFailureCount()==0&&s.getTestsSkippedCount()==0&&s.getTestsAbortedCount()==0;
        var result=new LinkedHashMap<String,Object>();result.put("runId",run==null?"UNVALIDATED":run);result.put("sourceIdentity",sourceIdentity==null?"UNVALIDATED":sourceIdentity);result.put("result",pass?"PASS":"FAIL");result.put("testsFound",s.getTestsFoundCount());result.put("testsSucceeded",s.getTestsSucceededCount());result.put("testsFailed",s.getTotalFailureCount());result.put("testsSkipped",s.getTestsSkippedCount());result.put("testsAborted",s.getTestsAbortedCount());
        result.put("scope","NEW_POSTGRES_CONSENT_METADATA_NOT_RPC_JWT_FINANCIAL_OR_EXECUTION_GATE");
        var failures=new ArrayList<Map<String,String>>();for(var f:s.getFailures()){Throwable t=f.getException();String state="",safeCode="";for(int n=0;t!=null&&n<8;n++,t=t.getCause()){
            if(t instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[A-Z0-9]{5}"))state=sql.getSQLState();
            if(!(t instanceof SQLException)&&t.getMessage()!=null&&t.getMessage().matches("C_CONSENT_PG_[A-Z0-9_]+"))safeCode=t.getMessage();
        }failures.add(Map.of("test",f.getTestIdentifier().getDisplayName(),"type",f.getException().getClass().getSimpleName(),"sqlState",state,"safeCode",safeCode));}result.put("failures",failures);
        String json=new ObjectMapper().writeValueAsString(result);Files.writeString(evidence.resolve("result.json"),json,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);System.out.println(json);System.exit(pass?0:1);
    }
}
