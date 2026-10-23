package com.pricetrack.exchange.custody.auth;

import com.pricetrack.exchange.custody.runtime.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import static org.junit.jupiter.api.Assertions.*;

/** H2 SQL behavior tests, never PostgreSQL acceptance or actual EOA/chain proof. */
public class CWalletLinkStoreTest {
    public static DataSource database(){
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:c_wallet_"+UUID.randomUUID().toString().replace("-","")+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){s.execute("CREATE TABLE public.users(id BIGINT PRIMARY KEY)");s.execute("INSERT INTO public.users VALUES(1),(2)");}catch(SQLException e){throw new IllegalStateException("TEST_H2_INIT");}return ds;
    }
    static final Instant NOW=Instant.parse("2026-10-23T00:00:00Z");
    static final ECKeyPair KEY=ECKeyPair.create(BigInteger.valueOf(321));
    static final String OWNER="0x"+Keys.getAddress(KEY);
    record Fixture(CCustodyIdentity identity,DataSource ds,Clock clock,CWalletLinkStore store,CWalletProofService service) {}
    static Fixture fixture(){var identity=CCustodyIdentityTest.sample();DataSource ds=database();Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);var store=new CWalletLinkStore(ds,identity,clock);store.installSchema();return new Fixture(identity,ds,clock,store,new CWalletProofService(identity,store,owner->{},clock));}
    @Test void proofAndLinkAreDeploymentScopedAndReplayIsBlocked(){var f=fixture();var p=f.service.challenge(1,OWNER,f.identity.origin());var link=f.service.link(1,p.id(),CWalletProofServiceTest.sign(p.message(),KEY),f.identity.origin());assertEquals(OWNER,link.wallet());assertTrue(f.store.challenge(1,p.id()).consumed());assertEquals(OWNER,f.store.find(1).orElseThrow().wallet());assertThrows(CCustodyFailure.class,()->f.service.link(1,p.id(),CWalletProofServiceTest.sign(p.message(),KEY),f.identity.origin()));var other=new CWalletLinkStore(f.ds,CCustodyIdentityTest.sample("http://localhost:5174"),f.clock);assertEquals(OWNER,other.find(1).orElseThrow().wallet());assertThrows(CCustodyFailure.class,()->other.challenge(2,p.id()));}
    @Test void newChallengeInvalidatesOnlyThisOwnerAndCannotBeCrossUsed(){var f=fixture();var a=f.service.challenge(1,OWNER,f.identity.origin());var b=f.service.challenge(2,OWNER,f.identity.origin());var a2=f.service.challenge(1,OWNER,f.identity.origin());assertTrue(f.store.challenge(1,a.id()).consumed());assertFalse(f.store.challenge(2,b.id()).consumed());assertFalse(f.store.challenge(1,a2.id()).consumed());assertThrows(CCustodyFailure.class,()->f.store.challenge(2,a2.id()));}
    @Test void concurrentUsersCannotLinkSameWalletTwice()throws Exception{
        var f=fixture();var p1=f.service.challenge(1,OWNER,f.identity.origin());var p2=f.service.challenge(2,OWNER,f.identity.origin());var ready=new CountDownLatch(2);var start=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        try{List<Future<Boolean>> results=new ArrayList<>();for(int user=1;user<=2;user++){int uid=user;var p=user==1?p1:p2;results.add(workers.submit(()->{ready.countDown();start.await();try{f.service.link(uid,p.id(),CWalletProofServiceTest.sign(p.message(),KEY),f.identity.origin());return true;}catch(CCustodyFailure safe){assertEquals(409,safe.status());return false;}}));}assertTrue(ready.await(3,TimeUnit.SECONDS));start.countDown();int success=0;for(var result:results)if(result.get(8,TimeUnit.SECONDS))success++;assertEquals(1,success);assertEquals(1,rows(f.ds,"SELECT count(*) FROM tms_c_identity.wallet_links"));assertEquals(1,rows(f.ds,"SELECT count(*) FROM tms_c_identity.wallet_challenges WHERE consumed=TRUE"));}finally{workers.shutdownNow();}
    }
    @Test void failedConsumeRollsBackInsertedLinkAndKeepsProofUnused(){
        var f=fixture();var p=f.service.challenge(1,OWNER,f.identity.origin());var guarded=new CWalletLinkStore(fault(f.ds,false),f.identity,f.clock);
        var failure=assertThrows(CCustodyFailure.class,()->guarded.link(1,p.id(),CWalletLinkStore.digest(p.message()),CWalletProofServiceTest.sign(p.message(),KEY),NOW));
        assertEquals("C_WALLET_STORAGE",failure.code());assertNull(failure.getCause());assertFalse(failure.toString().contains("private"));assertTrue(f.store.find(1).isEmpty());assertFalse(f.store.challenge(1,p.id()).consumed());
    }
    @Test void committedButLostResponseIsNotAutomaticallyRetriedOrErased(){
        var f=fixture();var p=f.service.challenge(1,OWNER,f.identity.origin());var guarded=new CWalletLinkStore(fault(f.ds,true),f.identity,f.clock);
        var failure=assertThrows(CCustodyFailure.class,()->guarded.link(1,p.id(),CWalletLinkStore.digest(p.message()),CWalletProofServiceTest.sign(p.message(),KEY),NOW));assertEquals("C_WALLET_COMMIT_UNKNOWN",failure.code());assertEquals(OWNER,f.store.find(1).orElseThrow().wallet());assertTrue(f.store.challenge(1,p.id()).consumed());assertEquals(1,rows(f.ds,"SELECT count(*) FROM tms_c_identity.wallet_links"));
    }
    @Test void ownerAndSignatureCannotBypassStoreDirectly(){var f=fixture();var p=f.service.challenge(1,OWNER,f.identity.origin());assertEquals("C_WALLET_INVALID_SIGNATURE",assertThrows(CCustodyFailure.class,()->f.store.link(1,p.id(),CWalletLinkStore.digest(p.message()),CWalletProofServiceTest.sign(p.message(),ECKeyPair.create(BigInteger.ONE)),NOW)).code());assertTrue(f.store.find(1).isEmpty());assertFalse(f.store.challenge(1,p.id()).consumed());}
    @Test void directStoreCannotPersistUnsupportedSignedMessagePrecision(){var f=fixture();String id=UUID.randomUUID().toString(),nonce="2".repeat(64);Instant issued=NOW.plusNanos(123456789),expires=issued.plusSeconds(120);String message=CWalletProofService.message(f.identity,id,1,OWNER,nonce,issued,expires);var p=new CWalletLinkStore.Challenge(id,f.identity.deploymentId(),1,OWNER,f.identity.origin(),message,CWalletLinkStore.digest(message),nonce,issued,expires,false);assertEquals("C_WALLET_INVALID_PROOF_SCOPE",assertThrows(CCustodyFailure.class,()->f.store.create(p)).code());assertEquals(0,rows(f.ds,"SELECT count(*) FROM tms_c_identity.wallet_challenges"));}
    static int rows(DataSource ds,String sql){try(Connection c=ds.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getInt(1);}catch(SQLException e){throw new AssertionError("TEST_H2_READ");}}
    static DataSource fault(DataSource source,boolean commitUnknown){
        AtomicBoolean fired=new AtomicBoolean();return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args)->{
            Object value=invoke(method,source,args);if(!method.getName().equals("getConnection"))return value;Connection c=(Connection)value;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(cp,cm,ca)->{
                if(commitUnknown&&cm.getName().equals("commit")&&fired.compareAndSet(false,true)){c.commit();throw new SQLException("private ambiguous detail","08006");}
                Object result=invoke(cm,c,ca);
                if(!commitUnknown&&cm.getName().equals("prepareStatement")&&ca[0].toString().contains("proof_signature=?")){
                    PreparedStatement s=(PreparedStatement)result;return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(sp,sm,sa)->{if(sm.getName().equals("executeUpdate")&&fired.compareAndSet(false,true))throw new SQLException("private failure","40001");return invoke(sm,s,sa);});}
                return result;
            });
        });
    }
    static Object invoke(Method method,Object target,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException cause){throw cause.getCause();}}
}
