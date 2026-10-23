package com.pricetrack.exchange.custody.direct;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.custody.abi.*;
import com.pricetrack.exchange.custody.abi.CAbiModels.*;
import com.pricetrack.exchange.custody.authorization.*;
import com.pricetrack.exchange.custody.direct.CDirectModels.*;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.custody.runtime.CCustodyRpcReader.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.runtime.FuturesMarketGate;
import java.lang.reflect.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import static org.junit.jupiter.api.Assertions.*;

/** H2 + controlled observations and a public trial Oracle key; NOT actual HTTP/PG/RPC/user wallet acceptance. */
public class CDirectServiceTest {
    static final Instant NOW=Instant.parse("2026-10-23T12:00:00Z");static final BigInteger E8=BigInteger.TEN.pow(8);
    static final ECKeyPair ORACLE=ECKeyPair.create(BigInteger.valueOf(23456));
    static String a(int n){return "0x"+String.format("%040x",n);}static String h(int n){return "0x"+String.format("%064x",n);}
    static CCustodyIdentity identity(){return new CCustodyIdentity(BigInteger.valueOf(31337),h(1),new CCustodyIdentity.Contract(a(1),h(2)),new CCustodyIdentity.Contract(a(2),h(3)),new CCustodyIdentity.Contract(a(3),h(4)),new CCustodyIdentity.Contract(a(4),h(5)),a(5),"0x"+Keys.getAddress(ORACLE),a(7),Hash.sha3String("mSEC"),h(6),"http://localhost:5173");}
    static final class Time extends Clock {Instant now=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
    static Request request(){return new Request("OPEN",null,"LONG","10","1000",E8.multiply(BigInteger.valueOf(90)).toString(),E8.multiply(BigInteger.valueOf(110)).toString(),"90","110");}
    static String sign(Report r){byte[] digest=Numeric.hexStringToByteArray(new CEip712Verifier().reportDigest(r,identity().chainId(),identity().rules().address()));var s=Sign.signMessage(digest,ORACLE,false);return Numeric.toHexString(s.getR())+Numeric.cleanHexPrefix(Numeric.toHexString(s.getS()))+Numeric.cleanHexPrefix(Numeric.toHexString(s.getV()));}
    static final class Fixture {
        final DataSource ds;final Time time=new Time();final CConsentStore drafts;final CDirectExecutionStore store;final AtomicInteger reads=new AtomicInteger(),signs=new AtomicInteger(),validations=new AtomicInteger();
        boolean oracleFail,wrongOracle,postOraclePriceChange;String linked=a(20);BigInteger nonce=BigInteger.ZERO;CDirectService service;
        Fixture(){var data=new JdbcDataSource();data.setURL("jdbc:h2:mem:c_direct_"+UUID.randomUUID().toString().replace("-","")+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");ds=data;
            try(var c=ds.getConnection();var s=c.createStatement()){
                s.execute("CREATE TABLE public.users(id BIGINT PRIMARY KEY); INSERT INTO public.users VALUES(1),(2)");
                s.execute("CREATE SCHEMA tms_c_identity; CREATE TABLE tms_c_identity.wallet_links(deployment_id CHAR(64),user_id BIGINT REFERENCES public.users(id),wallet CHAR(42),PRIMARY KEY(deployment_id,user_id),UNIQUE(deployment_id,wallet))");
                try(var p=c.prepareStatement("INSERT INTO tms_c_identity.wallet_links VALUES(?,?,?)")){p.setString(1,identity().deploymentId());p.setLong(2,1);p.setString(3,a(20));p.executeUpdate();p.setLong(2,2);p.setString(3,a(21));p.executeUpdate();}
                schema(s,"c-consent-schema.sql","-- PostgreSQL append-only enforcement:");schema(s,"c-direct-execution-schema.sql","-- PostgreSQL append-only enforcement:");
            }catch(Exception e){throw new AssertionError("H2_DIRECT_FIXTURE",e);}
            drafts=new CConsentStore(ds,identity(),time);store=new CDirectExecutionStore(ds,identity(),time);service=service(store);
        }
        CDirectService service(CDirectExecutionStore target){return new CDirectService(identity(),drafts,target,time,(id,c)->{signs.incrementAndGet();if(oracleFail)throw new IllegalStateException("PRIVATE_NOT_FOR_LOGS");return wrongOracle?"0x"+"00".repeat(65):sign(c.report());},uid->{if(uid!=1&&uid!=2)throw new CCustodyFailure("C_WALLET_NOT_LINKED",404);return uid==1?linked:a(21);},owner->{reads.incrementAndGet();return point(new Account(owner,"1000000","1000000","10000","0","10000","0","100000","0","110000","0","110000",nonce.toString()));},(owner,pos)->{reads.incrementAndGet();return point(position(owner,pos));},this::candidate,c->{validations.incrementAndGet();if(postOraclePriceChange)throw new com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure("PRICE_CHANGED");});}
        PositionView position(String owner,String pos){return new PositionView(pos,owner,CConsentModels.accountRef(owner,identity().symbolHash()),"0","10","100",E8.multiply(BigInteger.valueOf(100)).toString(),"1000","ACTIVE","1","0","0",Long.toString(NOW.minusSeconds(5).getEpochSecond()),"1000",false);}
        <T>Observation<T> point(T value){return new Observation<>(identity().deploymentId(),new Point("100",h(100),h(99),Long.toString(time.now.getEpochSecond())),time.now,value);}
        CReportCandidateBuilder.Candidate candidate(Intent i){reads.incrementAndGet();BigInteger price=E8.multiply(BigInteger.valueOf(100)),q=i.action().signum()==0?FuturesMath.quantity(i.collateralAfter(),10,price):i.quantityMin(),entry=i.action().signum()==0?price:i.entryPriceE8(),observed=BigInteger.valueOf(time.now.getEpochSecond());
            Report r=new Report(i.operationId(),i.positionId(),i.accountRef(),i.symbolHash(),i.action(),i.expectedVersion(),i.expectedCallEpoch(),i.side(),i.leverage(),q,entry,i.collateralBefore(),i.collateralAfter(),price,observed,observed.add(BigInteger.valueOf(30)),i.policyHash(),identity().controller().address());
            var p=i.action().signum()==0?new PositionView(i.positionId(),a(0),h(0),"0","0","0","0","0","NONE","0","0","0","0","0",false):position(i.owner(),i.positionId());
            return new CReportCandidateBuilder.Candidate(r,new CEip712Verifier().reportDigest(r,identity().chainId(),identity().rules().address()),new FuturesMarketGate.Mark(price,time.now,"SIMULATED",1L),point(new AdmissionPrestate(i,i.nonce().toString(),"10000","0","0",false,h(0),p)),FuturesMath.mark(FuturesMath.Side.LONG,q,entry,i.collateralAfter(),price));}
        static void schema(Statement s,String resource,String marker)throws Exception{try(var in=CDirectServiceTest.class.getResourceAsStream("/"+resource)){s.execute(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8).split(marker,2)[0]);}}
        int count(String table){try(var c=ds.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM tms_c_execution."+table)){r.next();return r.getInt(1);}catch(SQLException e){throw new AssertionError(e);}}
    }
    static String code(Runnable action){return assertThrows(CCustodyFailure.class,action::run).code();}
    @Test void draftReplayIsHistoricalBeforeRpcAndPublicIntegersAreStrings()throws Exception{var f=new Fixture();var d=f.service.draft(1,"direct-key-123",request());assertEquals(d.intentId(),d.intent().get("operationId"));assertEquals(a(20),d.intent().get("owner"));assertEquals("1000",d.intent().get("collateralAfter"));int reads=f.reads.get();f.time.now=NOW.plusSeconds(121);assertEquals(d,f.service.draft(1,"direct-key-123",request()));assertEquals(reads,f.reads.get());var j=new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(d));assertTrue(j.path("intent").path("nonce").isTextual());assertTrue(j.path("intent").path("deadline").isTextual());assertEquals(0,f.signs.get());assertEquals(1,f.count("drafts"));}
    @Test void changedConditionsAndInvalidNumericFormatsFailWithoutNewRpc(){var f=new Fixture();f.service.draft(1,"direct-key-123",request());int reads=f.reads.get();Request changed=new Request("OPEN",null,"SHORT","10","1000",request().priceMinE8(),request().priceMaxE8(),"90","110");assertEquals("C_DIRECT_IDEMPOTENCY_CONFLICT",code(()->f.service.draft(1,"direct-key-123",changed)));for(String amount:List.of("1e18","01","-1","0",BigInteger.ONE.shiftLeft(256).toString())){var invalid=new Request("OPEN",null,"LONG","10",amount,request().priceMinE8(),request().priceMaxE8(),"90","110");assertTrue(code(()->f.service.draft(1,"direct-key-456",invalid)).startsWith("C_DIRECT_INVALID_"));}assertEquals(reads,f.reads.get());}
    @Test void executionIsFixedDirectCalldataAndDoesNotRequireUserSignature(){var f=new Fixture();var d=f.service.draft(1,"direct-key-123",request());var e=f.service.execute(1,d.intentId());assertEquals(1,f.signs.get());assertEquals(1,f.validations.get());assertEquals(a(20),e.transaction().from());assertEquals(identity().controller().address(),e.transaction().to());assertEquals("31337",e.transaction().chainId());assertEquals("0x0",e.transaction().value());assertTrue(e.transaction().data().startsWith("0xc24ad93a"));assertInstanceOf(Direct.class,new CAbiCodec().decode(Target.CONTROLLER,e.transaction().data()));assertTrue(e.scope().contains("REQUIRES_USER_WALLET_APPROVAL"));assertEquals(0,f.count("consents"));assertEquals(1,f.count("direct_execution_claims"));assertEquals(1,f.count("direct_executions"));}
    @Test void repeatAfterExpiryOrRestartNeverRequestsAnotherOracleReport(){var f=new Fixture();var d=f.service.draft(1,"direct-key-123",request());var e=f.service.execute(1,d.intentId());int calls=f.reads.get();f.time.now=NOW.plusSeconds(121);assertEquals(e,f.service.execute(1,d.intentId()));assertEquals(e,f.service.findExecution(1,d.intentId()));assertEquals(e,f.service(new CDirectExecutionStore(f.ds,identity(),f.time)).execute(1,d.intentId()));assertEquals(1,f.signs.get());assertEquals(calls,f.reads.get());}
    @Test void crossUserAndChangedLinkedWalletCannotReadOrExecute(){var f=new Fixture();var d=f.service.draft(1,"direct-key-123",request());assertEquals("C_CONSENT_NOT_FOUND",code(()->f.service.findIntent(2,d.intentId())));assertEquals("C_CONSENT_NOT_FOUND",code(()->f.service.execute(2,d.intentId())));f.linked=a(22);assertEquals("C_DIRECT_OWNER_OR_ACTION",code(()->f.service.execute(1,d.intentId())));assertEquals(0,f.signs.get());}
    @Test void closeDraftRequiresExactOwnerPositionTerms(){var f=new Fixture();var r=new Request("CLOSE",h(44),"LONG","10","1000",request().priceMinE8(),request().priceMaxE8(),"100","100");var d=f.service.draft(1,"direct-close-1",r);assertEquals(h(44),d.intent().get("positionId"));assertEquals("1",d.intent().get("expectedVersion"));assertEquals("100",d.intent().get("quantityMin"));assertEquals(E8.multiply(BigInteger.valueOf(100)).toString(),d.intent().get("entryPriceE8"));assertNotNull(f.service.execute(1,d.intentId()));var bad=new Request("CLOSE",h(44),"LONG","10","1001",r.priceMinE8(),r.priceMaxE8(),"100","100");assertEquals("C_DIRECT_POSITION_TERMS",code(()->f.service.draft(1,"direct-close-2",bad)));}
    @Test void failedOrInvalidOracleLeavesClaimAndNeverAutomaticallyRetries(){for(boolean badSignature:List.of(false,true)){var f=new Fixture();var d=f.service.draft(1,"direct-failed-1",request());f.oracleFail=!badSignature;f.wrongOracle=badSignature;assertEquals("C_DIRECT_ORACLE_UNAVAILABLE_OR_INVALID",code(()->f.service.execute(1,d.intentId())));assertEquals("C_DIRECT_EXECUTION_CLAIMED_NOT_READY",code(()->f.service.execute(1,d.intentId())));assertEquals(1,f.signs.get());assertEquals(1,f.count("direct_execution_claims"));assertEquals(0,f.count("direct_executions"));}}
    @Test void postOracleMarketChangeRejectsWithoutNewPriceOrSignature(){var f=new Fixture();var d=f.service.draft(1,"direct-price-1",request());f.postOraclePriceChange=true;assertEquals("PRICE_CHANGED",assertThrows(com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure.class,()->f.service.execute(1,d.intentId())).getMessage());assertEquals("C_DIRECT_EXECUTION_CLAIMED_NOT_READY",code(()->f.service.execute(1,d.intentId())));assertEquals(1,f.signs.get());assertEquals(0,f.count("direct_executions"));}
    @Test void duplicateWorkersOnlyOneOracleRequest()throws Exception{var f=new Fixture();var d=f.service.draft(1,"direct-race-1",request());var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{List<Future<String>> tasks=new ArrayList<>();for(int n=0;n<2;n++)tasks.add(pool.submit(()->{start.await();try{f.service.execute(1,d.intentId());return "READY";}catch(CCustodyFailure safe){return safe.code();}}));start.countDown();var a=tasks.get(0).get(8,TimeUnit.SECONDS);var b=tasks.get(1).get(8,TimeUnit.SECONDS);assertTrue(List.of(a,b).contains("READY"));assertTrue(Set.of("READY","C_DIRECT_EXECUTION_CLAIMED_NOT_READY").contains(a));assertTrue(Set.of("READY","C_DIRECT_EXECUTION_CLAIMED_NOT_READY").contains(b));assertEquals(1,f.signs.get());assertEquals(1,f.count("direct_executions"));}finally{pool.shutdownNow();}}
    @Test void claimCommitUnknownPreventsOracleCallEvenIfClaimWasCommitted(){for(String mode:List.of("before","after")){var f=new Fixture();var d=f.service.draft(1,"direct-claim-1",request());var guarded=f.service(new CDirectExecutionStore(fault(f.ds,"direct_execution_claims",mode),identity(),f.time));assertEquals("C_DIRECT_COMMIT_UNKNOWN",code(()->guarded.execute(1,d.intentId())));assertEquals(0,f.signs.get());assertEquals(mode.equals("after")?1:0,f.count("direct_execution_claims"));if(mode.equals("after"))assertEquals("C_DIRECT_EXECUTION_CLAIMED_NOT_READY",code(()->f.service.execute(1,d.intentId())));}}
    @Test void readyCommitUnknownIsResolvedByReadWithoutSigningAgain(){var f=new Fixture();var d=f.service.draft(1,"direct-ready-1",request());var guarded=f.service(new CDirectExecutionStore(fault(f.ds,"direct_executions","after"),identity(),f.time));assertEquals("C_DIRECT_COMMIT_UNKNOWN",code(()->guarded.execute(1,d.intentId())));assertEquals(1,f.signs.get());assertEquals(1,f.count("direct_executions"));assertEquals(f.service.findExecution(1,d.intentId()),f.service.execute(1,d.intentId()));assertEquals(1,f.signs.get());}
    @Test void executionSaveRequiresClaimAndIntegrityChecksRejectCorruption(){var f=new Fixture();var d=f.service.draft(1,"direct-store-1",request());Intent i=f.drafts.findDraft(1,d.intentId()).intent();var c=f.candidate(i);var e=new Execution(i,c.report(),sign(c.report()),c.reportDigest(),c.market());assertEquals("C_DIRECT_CLAIM_REQUIRED",code(()->f.store.save(1,e)));f.service.execute(1,d.intentId());try(var connection=f.ds.getConnection();var s=connection.createStatement()){s.execute("UPDATE tms_c_execution.direct_executions SET execution_hash='"+"0".repeat(64)+"'");}catch(SQLException failure){throw new AssertionError(failure);}assertEquals("C_DIRECT_STORED_SCOPE",code(()->f.service.findExecution(1,d.intentId())));}
    @Test void lateReadyCommitAndCancelledNonceOverflowFailClosed(){var f=new Fixture();f.nonce=CAbiModels.UINT_MAX;assertEquals("C_DIRECT_NONCE_OVERFLOW",code(()->f.service.draft(1,"direct-overflow",request())));assertEquals(0,f.count("drafts"));f.nonce=BigInteger.ZERO;var d=f.service.draft(1,"direct-overflow",request());Intent i=f.drafts.findDraft(1,d.intentId()).intent();var c=f.candidate(i);var e=new Execution(i,c.report(),sign(c.report()),c.reportDigest(),c.market());assertTrue(f.store.claim(1,d.intentId()));f.time.now=NOW.plusSeconds(6);assertEquals("C_DIRECT_EXECUTION_EXPIRED",code(()->f.store.save(1,e)));assertEquals(0,f.count("direct_executions"));}
    @Test void appendOnlyClaimNeedsNoUpdatePrivilegeAndSaveRetainsUserLock(){
        var f=new Fixture();var d=f.service.draft(1,"direct-least-privilege",request());var locks=new AtomicInteger();
        DataSource restricted=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            Object value=invoke(m,f.ds,a);if(!m.getName().equals("getConnection"))return value;Connection c=(Connection)value;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p2,m2,a2)->{
                if(m2.getName().equals("prepareStatement")){
                    String sql=a2[0].toString();
                    if(sql.contains("direct_execution_claims")&&sql.contains("FOR UPDATE"))throw new SQLException("claim update denied","42501");
                    if(sql.equals("SELECT id FROM public.users WHERE id=? FOR UPDATE"))locks.incrementAndGet();
                }
                return invoke(m2,c,a2);
            });
        });
        var service=f.service(new CDirectExecutionStore(restricted,identity(),f.time));
        assertNotNull(service.execute(1,d.intentId()));assertEquals(2,locks.get());
        assertEquals(1,f.count("direct_execution_claims"));assertEquals(1,f.count("direct_executions"));assertEquals(1,f.signs.get());
    }
    static DataSource fault(DataSource ds,String table,String mode){var armed=new AtomicBoolean();var fired=new AtomicBoolean();return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{Object value=invoke(m,ds,a);if(!m.getName().equals("getConnection"))return value;Connection c=(Connection)value;return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p2,m2,a2)->{if(m2.getName().equals("commit")&&armed.get()&&fired.compareAndSet(false,true)){if(mode.equals("after"))c.commit();throw new SQLException("private simulated commit loss","08006");}Object result=invoke(m2,c,a2);if(m2.getName().equals("prepareStatement")&&a2[0].toString().startsWith("INSERT INTO tms_c_execution."+table+"("))armed.set(true);return result;});});}
    static Object invoke(Method m,Object target,Object[] args)throws Throwable{try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
}
