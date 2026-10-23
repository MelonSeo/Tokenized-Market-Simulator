package com.pricetrack.exchange.custody.store;

import static com.pricetrack.exchange.custody.model.CCustodyModels.*;
import static org.junit.jupiter.api.Assertions.*;

import com.pricetrack.exchange.custody.projection.CCustodyReducer;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual owned Linux-internal PostgreSQL only. Typed accounting fixtures, no RPC/chain claims. */
@EnabledIfEnvironmentVariable(named="C_CUSTODY_PG_TESTS",matches="true")
public class CCustodyProjectionStorePostgresTest {
    static DataSource data;
    static CCustodyReducer reducer;
    static CCustodyStateCodec codec;
    static CCustodyProjectionStore store;
    static final String OWNER=address(1), OTHER=address(2), TOKEN=address(3), VAULT=address(4), CONTROLLER=address(5);
    static final Block ANCHOR=new Block(0,hash(10),hash(9));
    static final Block BLOCK=new Block(1,hash(11),hash(10));
    static final String NS="tms_c_custody.";

    @BeforeAll static void guardedOwnedDatabase() throws Exception {
        if (!"/fixture/manifest.properties".equals(System.getenv("C_CUSTODY_FIXTURE"))) throw new IllegalStateException("C_PG_FIXTURE_PATH");
        Properties p=new Properties();
        try(var in=Files.newInputStream(Path.of("/fixture/manifest.properties"))) { p.load(in); }
        String run=p.getProperty("runId","");
        if(!run.matches("cpg-[0-9a-f]{16}")||!"C4B_PG_ONLY".equals(p.getProperty("scope"))) throw new IllegalStateException("C_PG_SCOPE");
        String suffix=run.substring(4),db="cpg_"+suffix,user="cowner_"+suffix;
        String url="jdbc:postgresql://pg:5432/"+db+"?sslmode=disable&connectTimeout=3&socketTimeout=8";
        String marker=p.getProperty("marker","");
        if(!db.equals(p.getProperty("database"))||!user.equals(p.getProperty("databaseUser"))
            ||!url.equals(p.getProperty("jdbcUrl"))||!"pg".equals(p.getProperty("pgHost"))
            ||!marker.matches("[0-9a-f]{64}")||!"/fixture/c-custody-schema.sql".equals(p.getProperty("schemaPath")))
            throw new IllegalStateException("C_PG_IDENTITY");
        String password=System.getenv("C_CUSTODY_PG_PASSWORD");
        if(password==null||password.length()<16) throw new IllegalStateException("C_PG_SECRET_MISSING");
        data=new DriverSource(url,user,password);
        // All checks are SELECTs and occur before the first product schema write.
        try(Connection c=data.getConnection();Statement s=c.createStatement()) {
            s.setQueryTimeout(3);
            try(ResultSet r=s.executeQuery("SELECT current_database(),current_user")) {
                if(!r.next()||!db.equals(r.getString(1))||!user.equals(r.getString(2))||r.next()) throw new IllegalStateException("C_PG_DATABASE_OWNER");
            }
            try(ResultSet r=s.executeQuery("SELECT scope,run_id,marker FROM public.c_fixture_identity")) {
                if(!r.next()||!"C4B_PG_ONLY".equals(r.getString(1))||!run.equals(r.getString(2))||!marker.equals(r.getString(3))||r.next())
                    throw new IllegalStateException("C_PG_MARKER");
            }
            try(ResultSet r=s.executeQuery("SELECT count(*)>0 AND bool_and(auth_method='scram-sha-256') FROM pg_hba_file_rules WHERE type IN ('host','hostssl','hostnossl')")) {
                if(!r.next()||!r.getBoolean(1)) throw new IllegalStateException("C_PG_HOST_AUTH");
            }
            // Return only a boolean; never read or log the stored verifier/hash itself.
            try(ResultSet r=s.executeQuery("SELECT rolpassword LIKE 'SCRAM-SHA-256$%' FROM pg_authid WHERE rolname=current_user")) {
                if(!r.next()||!r.getBoolean(1)||r.next()) throw new IllegalStateException("C_PG_SCRAM_VERIFIER");
            }
        }
        try(var in=CCustodyProjectionStore.class.getResourceAsStream("/c-custody-schema.sql")) {
            if(in==null||!Arrays.equals(in.readAllBytes(),Files.readAllBytes(Path.of(p.getProperty("schemaPath")))))
                throw new IllegalStateException("C_PG_SCHEMA_IDENTITY");
        }
        reducer=new CCustodyReducer(new CCustodyReducer.Limits(128,32,32,32,32));
        codec=new CCustodyStateCodec(reducer); store=new CCustodyProjectionStore(data,codec,reducer);
        // This sentinel belongs to the new fixture; verify C SQL does not touch unrelated tables.
        sql("CREATE TABLE IF NOT EXISTS public.c_unrelated_sentinel(id INTEGER PRIMARY KEY,value TEXT NOT NULL)");
        sql("INSERT INTO public.c_unrelated_sentinel VALUES(1,'preserve-me') ON CONFLICT(id) DO NOTHING");
        store.installSchema();
    }

    @Test void additiveInstallRepeatedPreservesUnrelatedAndExistingEvidence() throws Exception {
        Deployment d=deployment(); var first=store.bootstrap(d,ANCHOR);
        store.installSchema(); var replay=store.bootstrap(d,ANCHOR);
        assertEquals(first,replay);
        assertEquals("preserve-me",scalar("SELECT value FROM public.c_unrelated_sentinel WHERE id=1"));
        assertEquals(1L,count("deployments",d.id()));
    }

    @Test void realClaimsPipelineRoundTripsAndFreshStoreRestartsWithoutBUserOrRaw() throws Exception {
        Deployment d=deployment(); var initial=store.bootstrap(d,ANCHOR);
        List<Bundle> path=claimsPath(d);
        var result=store.append(d.id(),initial.head(),path);
        assertEquals(bi(25),result.state().totalUnpaid());
        assertEquals(bi(1_175),result.state().totalFree());
        assertEquals(bi(975),result.state().balances().get(OWNER).free());
        assertEquals(bi(200),result.state().balances().get(OTHER).free());
        assertEquals(bi(25),result.state().claims().get(BigInteger.ONE).remaining());
        assertEquals(bi(25),result.state().claims().get(BigInteger.ONE).paid());
        assertFalse(result.state().completeCoverage()); assertTrue(result.state().verificationScope().contains("NOT verified"));
        var restarted=new CCustodyProjectionStore(data,new CCustodyStateCodec(reducer),reducer).load(d.id());
        assertEquals(result,restarted); assertEquals(8L,count("chain_observations",d.id()));
        assertEquals(2L,count("projection_snapshots",d.id()));
        assertEquals(result,store.bootstrap(d,ANCHOR)); // same bootstrap does not rewind current head
    }

    @Test void exactFullReplayIsNoopEvenWithStaleExpectedHead() throws Exception {
        Deployment d=deployment(); var initial=store.bootstrap(d,ANCHOR);
        Bundle b=deposit(d,0,0,OWNER,100);
        var first=store.append(d.id(),initial.head(),List.of(b));
        var replay=store.append(d.id(),initial.head(),List.of(b));
        assertEquals(first,replay); assertEquals(1L,count("chain_observations",d.id()));
        assertEquals(2L,count("projection_snapshots",d.id()));
    }

    @Test void sameObservationDifferentPayloadOrDeploymentAndAnchorCannotOverwrite() throws Exception {
        Deployment d=deployment(); var initial=store.bootstrap(d,ANCHOR); Bundle b=deposit(d,0,0,OWNER,100);
        var first=store.append(d.id(),initial.head(),List.of(b));
        Bundle conflict=deposit(d,0,0,OWNER,101);
        assertEquals("C_STORE_OBSERVATION_CONFLICT",assertThrows(CCustodyProjectionStore.Failure.class,
            ()->store.append(d.id(),first.head(),List.of(conflict))).code());
        Deployment changed=new Deployment(d.id(),d.chainId(),hash(999),d.token(),d.vault(),d.controller(),d.rules(),d.relayer(),d.pauseAdmin(),d.oracleSigner(),d.policyHash());
        assertEquals("C_STORE_DEPLOYMENT_CONFLICT",assertThrows(CCustodyProjectionStore.Failure.class,()->store.bootstrap(changed,ANCHOR)).code());
        assertEquals("C_STORE_ANCHOR_CONFLICT",assertThrows(CCustodyProjectionStore.Failure.class,
            ()->store.bootstrap(d,new Block(0,hash(99),hash(9)))).code());
        assertEquals(first,store.load(d.id()));
    }

    @Test void twoWorkersSameBatchCreditOnceAndDifferentBatchStaleWriterFails() throws Exception {
        Deployment d=deployment(); var initial=store.bootstrap(d,ANCHOR); Bundle b=deposit(d,0,0,OWNER,100);
        List<Object> duplicate=race(()->store.append(d.id(),initial.head(),List.of(b)),()->store.append(d.id(),initial.head(),List.of(b)));
        assertTrue(duplicate.stream().noneMatch(x->x instanceof Throwable)); assertEquals(duplicate.get(0),duplicate.get(1));
        assertEquals(bi(100),store.load(d.id()).state().totalFree()); assertEquals(1L,count("chain_observations",d.id()));
        Deployment next=deployment(); var anchor=store.bootstrap(next,ANCHOR);
        List<Object> competing=race(()->store.append(next.id(),anchor.head(),List.of(deposit(next,0,0,OWNER,100))),
            ()->store.append(next.id(),anchor.head(),List.of(deposit(next,0,0,OTHER,200))));
        assertEquals(1L,competing.stream().filter(x->x instanceof CCustodyProjectionStore.Snapshot).count());
        assertEquals(1L,competing.stream().filter(x->x instanceof CCustodyProjectionStore.Failure f&&f.code().equals("C_STORE_STALE_HEAD")).count());
        assertEquals(1L,count("chain_observations",next.id()));
    }

    @Test void mixedOldAndNewBatchDoesNotBypassStaleHead() throws Exception {
        Deployment d=deployment(); var initial=store.bootstrap(d,ANCHOR); Bundle b=deposit(d,0,0,OWNER,100);
        var first=store.append(d.id(),initial.head(),List.of(b));
        assertEquals("C_STORE_STALE_HEAD",assertThrows(CCustodyProjectionStore.Failure.class,
            ()->store.append(d.id(),initial.head(),List.of(b,deposit(d,1,2,OTHER,200)))).code());
        assertEquals(first,store.load(d.id())); assertEquals(1L,count("chain_observations",d.id()));
    }

    @Test void invalidSecondBundleRollsBackFirstObservationAndProjection() throws Exception {
        Deployment d=deployment(); var initial=store.bootstrap(d,ANCHOR);
        Bundle good=deposit(d,0,0,OWNER,100);
        Bundle invalid=deposit(d,1,2,OTHER,200);
        invalid=new Bundle(invalid.deployment(),invalid.block(),invalid.transactionHash(),invalid.transactionIndex(),
            invalid.from(),invalid.to(),invalid.outcome(),invalid.call(),List.of());
        Bundle bad=invalid;
        assertThrows(IllegalArgumentException.class,()->store.append(d.id(),initial.head(),List.of(good,bad)));
        assertEquals(initial,store.load(d.id())); assertEquals(0L,count("chain_observations",d.id()));
        assertEquals(1L,count("projection_snapshots",d.id()));
    }

    @Test void headUpdateFailureRollsBackAllInsertsAndCommitReplyLossReplaysOnce() throws Exception {
        Deployment d=deployment(); var initial=store.bootstrap(d,ANCHOR); Bundle b=deposit(d,0,0,OWNER,100);
        var before=new CCustodyProjectionStore(new FaultSource(data,"HEAD_UPDATE"),codec,reducer);
        var failure=assertThrows(CCustodyProjectionStore.Failure.class,()->before.append(d.id(),initial.head(),List.of(b)));
        assertEquals("C_STORE_SQL_FAILURE",failure.code()); assertFalse(failure.commitUncertain());
        assertEquals(initial,store.load(d.id())); assertEquals(0L,count("chain_observations",d.id()));
        var closing=new CCustodyProjectionStore(new FaultSource(data,"HEAD_AND_CLOSE"),codec,reducer);
        var sanitized=assertThrows(CCustodyProjectionStore.Failure.class,()->closing.append(d.id(),initial.head(),List.of(b)));
        assertEquals("C_STORE_SQL_FAILURE",sanitized.code()); assertEquals("40001",sanitized.sqlState());
        assertNull(sanitized.getCause()); assertEquals(0,sanitized.getSuppressed().length);
        assertFalse(sanitized.toString().contains("SENSITIVE_CLOSE_SENTINEL"));
        assertEquals(initial,store.load(d.id())); assertEquals(0L,count("chain_observations",d.id()));
        var after=new CCustodyProjectionStore(new FaultSource(data,"COMMIT_AFTER"),codec,reducer);
        var lost=assertThrows(CCustodyProjectionStore.Failure.class,()->after.append(d.id(),initial.head(),List.of(b)));
        assertTrue(lost.commitUncertain()); assertEquals("08006",lost.sqlState()); assertNull(lost.getCause());
        var committed=store.load(d.id()); assertEquals(bi(100),committed.state().totalFree());
        assertEquals(committed,store.append(d.id(),initial.head(),List.of(b)));
        assertEquals(1L,count("chain_observations",d.id())); assertEquals(2L,count("projection_snapshots",d.id()));
    }

    @Test void appendOnlyTriggersAndForeignKeysAreActualPostgresConstraints() throws Exception {
        Deployment d=deployment(); store.bootstrap(d,ANCHOR);
        for(String table:List.of("deployments","canonical_epochs","chain_observations","projection_snapshots")) {
            for(String verb:List.of("UPDATE "+NS+table+" SET "+(table.equals("deployments")?"deployment_id=deployment_id":"deployment_id=deployment_id"),
                "DELETE FROM "+NS+table,"TRUNCATE "+NS+table+" CASCADE")) {
                SQLException e=assertThrows(SQLException.class,()->sql(verb)); assertEquals("55000",e.getSQLState());
            }
        }
        SQLException fk=assertThrows(SQLException.class,()->sql("INSERT INTO "+NS+"projection_heads VALUES('missing',1,0,'"+"0".repeat(64)+"')"));
        assertEquals("23503",fk.getSQLState());
        SQLException check=assertThrows(SQLException.class,()->sql("INSERT INTO "+NS+"deployments VALUES('badcodec',2,decode('00','hex'),'"+"0".repeat(64)+"')"));
        assertEquals("23514",check.getSQLState());
        assertEquals("preserve-me",scalar("SELECT value FROM public.c_unrelated_sentinel WHERE id=1"));
    }

    @Test void explicitEmptyBlockAdvancesOnceAndReadDetectsCorruptPayload() throws Exception {
        Deployment d=deployment(); var initial=store.bootstrap(d,ANCHOR);
        var advanced=store.advanceEmpty(d.id(),initial.head(),BLOCK);
        assertEquals(advanced,store.advanceEmpty(d.id(),initial.head(),BLOCK));
        assertEquals(0L,count("chain_observations",d.id()));
        // Only a NEW fixture's mutable head is redirected to intentionally corrupt new evidence.
        sql("INSERT INTO "+NS+"projection_snapshots VALUES('"+d.id()+"',1,99,1,'"+BLOCK.hash()+"',-1,1,decode('00','hex'),'"+"0".repeat(64)+"')");
        sql("UPDATE "+NS+"projection_heads SET revision=99,state_hash='"+"0".repeat(64)+"' WHERE deployment_id='"+d.id()+"'");
        assertEquals("C_STORE_PAYLOAD_INTEGRITY",assertThrows(CCustodyProjectionStore.Failure.class,()->store.load(d.id())).code());
    }

    static List<Bundle> claimsPath(Deployment d) {
        String op=hash(501),closeOp=hash(502),pos=hash(400),intent=hash(601),report=hash(602);
        Bundle deposit=deposit(d,0,0,OWNER,1_000);
        Bundle funding=bundle(d,1,2,OTHER,VAULT,new VaultCall(VaultAction.FUND_HOUSE,OTHER,bi(50)),
            List.of(new Transfer(OTHER,VAULT,bi(50)),new HouseFunded(OTHER,bi(50))),List.of(TOKEN,VAULT));
        Bundle open=bundle(d,2,4,OWNER,CONTROLLER,new UserCall(UserAction.OPEN,OWNER,op,pos,ZERO,ZERO,ZERO,bi(100),intent,report,true),
            List.of(new RuleApplied(op,pos,"OPEN",bi(1),bi(100),ZERO,ZERO,ZERO,ZERO),new ReportConsumed(op,bi(1_000),bi(1_030)),
                new Locked(op,pos,OWNER,bi(100),bi(100)),new UserExecuted(op,pos,OWNER,intent,report,"OPEN",ZERO,true)),
            List.of(d.rules(),d.rules(),VAULT,CONTROLLER));
        Bundle close=bundle(d,3,8,OWNER,CONTROLLER,new UserCall(UserAction.CLOSE,OWNER,closeOp,pos,bi(1),bi(1),bi(100),bi(100),intent,report,true),
            List.of(new RuleApplied(closeOp,pos,"CLOSE",bi(2),bi(100),bi(100),ZERO,ZERO,ZERO),new ReportConsumed(closeOp,bi(1_000),bi(1_030)),
                new ClaimCreated(bi(1),OWNER,pos,closeOp,bi(50)),new PositionSettled(closeOp,pos,OWNER,bi(100),bi(100),ZERO,bi(50),bi(1)),
                new UserExecuted(closeOp,pos,OWNER,intent,report,"CLOSE",bi(1),true)),
            List.of(d.rules(),d.rules(),VAULT,VAULT,CONTROLLER));
        Bundle secondOwner=deposit(d,4,13,OTHER,200);
        Bundle refill=bundle(d,5,15,OTHER,VAULT,new VaultCall(VaultAction.FUND_HOUSE,OTHER,bi(25)),
            List.of(new Transfer(OTHER,VAULT,bi(25)),new HouseFunded(OTHER,bi(25))),List.of(TOKEN,VAULT));
        Bundle partial=bundle(d,6,17,OTHER,VAULT,new ProcessClaimCall(),List.of(new ClaimPaid(bi(1),OWNER,bi(25),bi(25))),List.of(VAULT));
        Bundle withdraw=bundle(d,7,18,OWNER,VAULT,new VaultCall(VaultAction.WITHDRAW,OWNER,bi(100)),
            List.of(new Transfer(VAULT,OWNER,bi(100)),new Withdrawn(OWNER,bi(100))),List.of(TOKEN,VAULT));
        return List.of(deposit,funding,open,close,secondOwner,refill,partial,withdraw);
    }
    static Bundle deposit(Deployment d,int index,int logIndex,String owner,long value) {
        return bundle(d,index,logIndex,owner,VAULT,new VaultCall(VaultAction.DEPOSIT,owner,bi(value)),
            List.of(new Transfer(owner,VAULT,bi(value)),new Deposited(owner,bi(value))),List.of(TOKEN,VAULT));
    }
    static Bundle bundle(Deployment d,int index,int logIndex,String from,String to,Call call,List<Event> events,List<String> emitters) {
        List<Log> logs=new ArrayList<>();for(int n=0;n<events.size();n++)logs.add(new Log(logIndex+n,emitters.get(n),events.get(n)));
        return new Bundle(d,BLOCK,hash(100+index),index,from,to,Outcome.SUCCESS,call,logs);
    }
    static Deployment deployment() { return new Deployment("pg-"+UUID.randomUUID(),bi(31337),hash(900),TOKEN,VAULT,CONTROLLER,address(6),address(7),address(8),address(9),hash(901)); }
    static BigInteger bi(long n) { return BigInteger.valueOf(n); }
    static String address(int n) { return "0x"+String.format("%040x",n); }
    static String hash(int n) { return "0x"+String.format("%064x",n); }
    static void sql(String sql) throws SQLException { try(Connection c=data.getConnection();Statement s=c.createStatement()){s.setQueryTimeout(3);s.execute(sql);} }
    static String scalar(String query) throws SQLException {try(Connection c=data.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(query)){r.next();return r.getString(1);} }
    static long count(String table,String id) throws SQLException {
        try(Connection c=data.getConnection();PreparedStatement s=c.prepareStatement("SELECT count(*) FROM "+NS+table+" WHERE deployment_id=?")) {
            s.setString(1,id);try(ResultSet r=s.executeQuery()){r.next();return r.getLong(1);}
        }
    }
    static List<Object> race(Callable<?> left,Callable<?> right) throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try {
            List<Future<Object>> jobs=new ArrayList<>();
            for(Callable<?> task:List.of(left,right))jobs.add(workers.submit(()->{ready.countDown();if(!go.await(5,TimeUnit.SECONDS))throw new IllegalStateException("C_TEST_BARRIER");try{return task.call();}catch(RuntimeException e){return e;}}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            return List.of(jobs.get(0).get(15,TimeUnit.SECONDS),jobs.get(1).get(15,TimeUnit.SECONDS));
        } finally { workers.shutdownNow(); }
    }
    static class DriverSource implements DataSource {
        final String url,user,password;
        DriverSource(String url,String user,String password){this.url=url;this.user=user;this.password=password;}
        public Connection getConnection() throws SQLException{return DriverManager.getConnection(url,user,password);}
        public Connection getConnection(String u,String p) throws SQLException{throw new SQLException("C_TEST_CREDENTIAL_OVERRIDE_DISABLED");}
        public PrintWriter getLogWriter(){return null;} public void setLogWriter(PrintWriter w){}
        public void setLoginTimeout(int seconds){} public int getLoginTimeout(){return 3;}
        public Logger getParentLogger(){return Logger.getLogger("c-test");}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("C_TEST_NO_UNWRAP");}
        public boolean isWrapperFor(Class<?> type){return false;}
    }
    static final class FaultSource implements DataSource {
        final DataSource delegate;final String fault;
        FaultSource(DataSource delegate,String fault){this.delegate=delegate;this.fault=fault;}
        public Connection getConnection() throws SQLException {
            Connection actual=delegate.getConnection();
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try {
                    if(method.getName().equals("commit")&&fault.equals("COMMIT_AFTER")){actual.commit();throw new SQLException("C_TEST_REPLY_LOST","08006");}
                    if(method.getName().equals("close")&&fault.equals("HEAD_AND_CLOSE")) {
                        actual.close(); throw new SQLException("SENSITIVE_CLOSE_SENTINEL","08006");
                    }
                    if(method.getName().equals("prepareStatement")&&(fault.equals("HEAD_UPDATE")||fault.equals("HEAD_AND_CLOSE"))&&((String)args[0]).startsWith("UPDATE "+NS+"projection_heads"))
                        throw new SQLException("C_TEST_INJECTED","40001");
                    return method.invoke(actual,args);
                }catch(InvocationTargetException e){throw e.getCause();}
            });
        }
        public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("C_TEST_NO_OVERRIDE");}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter w){}
        public void setLoginTimeout(int seconds){}public int getLoginTimeout(){return 3;}
        public Logger getParentLogger(){return Logger.getLogger("c-test");}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("C_TEST_NO_UNWRAP");}
        public boolean isWrapperFor(Class<?> type){return false;}
    }
}
