package com.pricetrack.exchange.custody.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.custody.runtime.CCustodyRpcReader.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static com.pricetrack.exchange.custody.authorization.CConsentModelsTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** H2 plus an explicit trusted observation seam, NOT actual PG constraints, RPC or execution. */
public class CNonceAdmissionStoreTest {
    record Fixture(CConsentStoreTest.Fixture consent,CNonceAdmissionStore store,AtomicInteger calls){}
    static Observation<AdmissionPrestate> observation(Intent i,Instant time){
        var p=new PositionView(i.positionId(),addr(0),hash(0),"0","0","0","0","0","NONE","0","0","0","0","0",false);
        return new Observation<>(identity().deploymentId(),new Point("123",hash(123),hash(122),Long.toString(time.getEpochSecond())),time,
            new AdmissionPrestate(i,i.nonce().toString(),"1000000","0","0",false,hash(0),p));
    }
    static Fixture fixture(){
        var f=CConsentStoreTest.fixture();try(var c=f.ds().getConnection();var s=c.createStatement();var in=CNonceAdmissionStoreTest.class.getResourceAsStream("/c-nonce-admission-schema.sql")){
            s.execute(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8).split("-- PostgreSQL append-only enforcement:",2)[0]);
        }catch(Exception e){throw new AssertionError("H2_ADMISSION_FIXTURE",e);}
        var calls=new AtomicInteger();var store=new CNonceAdmissionStore(f.ds(),identity(),f.time(),i->{calls.incrementAndGet();return observation(i,f.time().value);});
        return new Fixture(f,store,calls);
    }
    static void consent(Fixture f,Intent i,String key){var d=f.consent.store().createDraft(1,key,i);f.consent.store().acceptConsent(1,i.operationId(),d.intentDigest(),signature(i,KEY));}
    static int count(Fixture f){return CConsentStoreTest.count(f.consent.ds(),"nonce_admissions");}
    static String code(Runnable action){return assertThrows(CCustodyFailure.class,action::run).code();}
    static CNonceAdmissionStore store(Fixture f,Function<Intent,Observation<AdmissionPrestate>> provider){return new CNonceAdmissionStore(f.consent.ds(),identity(),f.consent.time(),provider);}
    @Test void exactConsentAdmissionIsDurableAndHistoricalNotRenewed()throws Exception{
        var f=fixture();var i=intent();consent(f,i,"admit-request-1");var a=f.store.admit(1,i.operationId());assertEquals(1,count(f));assertEquals(1,f.calls.get());
        f.consent.time().value=NOW.plusSeconds(121);assertEquals(a,f.store.admit(1,i.operationId()));assertEquals(a,f.store.find(1,i.operationId()).orElseThrow());assertEquals(1,f.calls.get());
        var restarted=store(f,v->{throw new AssertionError("HISTORICAL_READ_MUST_NOT_RPC");});assertEquals(a,restarted.admit(1,i.operationId()));
        var json=new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());var tree=json.readTree(json.writeValueAsString(a));assertEquals(CNonceAdmissionModels.SCOPE,tree.get("scope").asText());assertFalse(tree.toString().contains(signature(i,KEY)));assertFalse(a.toString().contains(OWNER));
    }
    @Test void missingConsentAndCrossUserNeverReadRpc(){var f=fixture();var i=intent();assertEquals("C_NONCE_ADMISSION_CONSENT_NOT_FOUND",code(()->f.store.admit(1,i.operationId())));f.consent.store().createDraft(1,"draft-only-1",i);assertEquals("C_NONCE_ADMISSION_CONSENT_NOT_FOUND",code(()->f.store.admit(1,i.operationId())));f.consent.store().acceptConsent(1,i.operationId(),CConsentModels.digest(identity(),i),signature(i,KEY));assertEquals("C_NONCE_ADMISSION_CONSENT_NOT_FOUND",code(()->f.store.admit(2,i.operationId())));assertEquals(0,f.calls.get());assertEquals(0,count(f));}
    @Test void firstOfDifferentConsentsWithSameNonceWins(){var f=fixture();var i=intent();var j=change(i,"operationId",hash(30));consent(f,i,"admission-op-a");consent(f,j,"admission-op-b");f.store.admit(1,i.operationId());assertEquals("C_NONCE_ADMISSION_NONCE_CONFLICT",code(()->f.store.admit(1,j.operationId())));assertEquals(1,count(f));assertTrue(f.store.find(1,j.operationId()).isEmpty());}
    @Test void nonceAboveJavascriptPrecisionIsStoredExactly(){var f=fixture();var i=change(intent(),"nonce",BigInteger.ONE.shiftLeft(200));consent(f,i,"admit-big-nonce");var a=f.store.admit(1,i.operationId());assertEquals(i.nonce().toString(),a.userNonce());assertEquals(a,f.store.find(1,i.operationId()).orElseThrow());}
    @Test void expiryBeforeAndDuringRpcCreatesNoSlot(){var f=fixture();var i=intent();consent(f,i,"admit-expired");f.consent.time().value=NOW.plusSeconds(121);assertEquals("C_CONSENT_INTENT_WINDOW",code(()->f.store.admit(1,i.operationId())));assertEquals(0,f.calls.get());f.consent.time().value=NOW;var s=store(f,v->{f.consent.time().value=NOW.plusSeconds(121);return observation(v,NOW);});assertEquals("C_CONSENT_INTENT_WINDOW",code(()->s.admit(1,i.operationId())));assertEquals(0,count(f));
        f.consent.time().value=NOW.plusSeconds(1);var rollback=store(f,v->{f.consent.time().value=NOW;return observation(v,NOW);});assertEquals("C_NONCE_ADMISSION_OBSERVATION_EXPIRED",code(()->rollback.admit(1,i.operationId())));assertEquals(0,count(f));}
    @Test void wrongDeploymentNonceIntentAndExpiredObservationFailClosed(){var f=fixture();var i=intent();consent(f,i,"admit-scope-test");var good=observation(i,NOW);
        List<Observation<AdmissionPrestate>> bad=List.of(new Observation<>("0".repeat(64),good.block(),NOW,good.value()),
            new Observation<>(good.deploymentId(),good.block(),NOW,new AdmissionPrestate(i,"1","1000000","0","0",false,hash(0),good.value().position())),
            observation(change(i,"positionId",hash(88)),NOW),new Observation<>(good.deploymentId(),good.block(),NOW.minusSeconds(15),good.value()),new Observation<>(good.deploymentId(),good.block(),NOW.plusNanos(1),good.value()));
        for(var b:bad)assertTrue(code(()->store(f,v->b).admit(1,i.operationId())).startsWith("C_NONCE_ADMISSION_OBSERVATION_"));assertEquals(0,count(f));
    }
    @Test void nonceMaxCannotBeAdmitted(){var f=fixture();var i=change(intent(),"nonce",com.pricetrack.exchange.custody.abi.CAbiModels.UINT_MAX);consent(f,i,"admit-max-nonce");assertEquals("C_NONCE_ADMISSION_OBSERVATION_SCOPE",code(()->f.store.admit(1,i.operationId())));assertEquals(0,count(f));}
    @Test void walletChangeWhileRpcInFlightIsRechecked(){var f=fixture();var i=intent();consent(f,i,"admit-wallet-change");var s=store(f,v->{try(var c=f.consent.ds().getConnection();var p=c.prepareStatement("UPDATE tms_c_identity.wallet_links SET wallet=? WHERE user_id=1")){p.setString(1,addr(77));p.executeUpdate();}catch(SQLException e){throw new AssertionError(e);}return observation(v,NOW);});assertEquals("C_NONCE_ADMISSION_SCOPE_CONFLICT",code(()->s.admit(1,i.operationId())));assertEquals(0,count(f));}
    @Test void rpcFailureDoesNotCreateOrReleaseSlot(){var f=fixture();var i=intent();consent(f,i,"admit-rpc-failure");assertEquals("C_RPC_TIMEOUT",code(()->store(f,v->{throw new CCustodyFailure("C_RPC_TIMEOUT",503);}).admit(1,i.operationId())));assertEquals(0,count(f));var a=f.store.admit(1,i.operationId());assertEquals(a,store(f,v->{throw new AssertionError("NO_RETRANSMIT");}).admit(1,i.operationId()));assertEquals(1,count(f));}
    @Test void twoWorkersSameOperationAreIdempotent()throws Exception{var f=fixture();var i=intent();consent(f,i,"admit-same-worker");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->{start.await();return f.store.admit(1,i.operationId());});var b=pool.submit(()->{start.await();return f.store.admit(1,i.operationId());});start.countDown();assertEquals(a.get(8,TimeUnit.SECONDS),b.get(8,TimeUnit.SECONDS));assertEquals(1,count(f));}finally{pool.shutdownNow();}}
    @Test void twoWorkersDifferentOperationsCannotDoubleSelectNonce()throws Exception{var f=fixture();var i=intent();var j=change(i,"operationId",hash(31));consent(f,i,"admit-race-a");consent(f,j,"admit-race-b");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{List<Future<String>> tasks=new ArrayList<>();for(var v:List.of(i,j))tasks.add(pool.submit(()->{start.await();try{f.store.admit(1,v.operationId());return "OK";}catch(CCustodyFailure e){return e.code();}}));start.countDown();List<String> r=List.of(tasks.get(0).get(8,TimeUnit.SECONDS),tasks.get(1).get(8,TimeUnit.SECONDS));assertEquals(1,r.stream().filter("OK"::equals).count());assertEquals(1,r.stream().filter("C_NONCE_ADMISSION_NONCE_CONFLICT"::equals).count());assertEquals(1,count(f));}finally{pool.shutdownNow();}}
    @Test void insertFailureRollsBackAndCommitUnknownRemainsObserveFirst(){for(String mode:List.of("insert","before","after")){var f=fixture();var i=intent();consent(f,i,"admit-fault-"+mode);var s=new CNonceAdmissionStore(fault(f.consent.ds(),mode),identity(),f.consent.time(),v->observation(v,NOW));assertEquals(mode.equals("insert")?"C_NONCE_ADMISSION_STORAGE":"C_NONCE_ADMISSION_COMMIT_UNKNOWN",code(()->s.admit(1,i.operationId())));assertEquals(mode.equals("after")?1:0,count(f));if(mode.equals("after"))assertEquals(f.store.find(1,i.operationId()).orElseThrow(),f.store.admit(1,i.operationId()));}}
    @Test void corruptedStoredObservationCannotBeTreatedAsAdmission(){
        var f=fixture();var i=intent();consent(f,i,"admit-corruption");f.store.admit(1,i.operationId());
        try(var c=f.consent.ds().getConnection();var s=c.createStatement()){
            s.execute("UPDATE tms_c_execution.nonce_admissions SET observation_hash='"+"0".repeat(64)+"'");
        }catch(SQLException e){throw new AssertionError(e);}
        assertEquals("C_NONCE_ADMISSION_STORED_SCOPE",code(()->f.store.find(1,i.operationId())));
        // Deliberate H2-only corruption bypasses PG triggers. Even matching hashes must not authorize null data.
        for(String malformed:List.of("null","{\"deploymentId\":\""+identity().deploymentId()+"\",\"value\":null}")){
            try(var c=f.consent.ds().getConnection();var s=c.prepareStatement("UPDATE tms_c_execution.nonce_admissions SET observation_json=?,observation_hash=?")){
                s.setString(1,malformed);s.setString(2,CConsentModels.sha(malformed));s.executeUpdate();
            }catch(SQLException e){throw new AssertionError(e);}
            assertEquals("C_NONCE_ADMISSION_STORED_SCOPE",code(()->f.store.find(1,i.operationId())));
        }
        assertEquals(1,count(f));
    }
    @Test void productionConstructorDoesNotAcceptCallerProvidedObservations(){var constructors=CNonceAdmissionStore.class.getConstructors();assertEquals(1,constructors.length);assertArrayEquals(new Class<?>[]{DataSource.class,CCustodyRpcReader.class,Clock.class},constructors[0].getParameterTypes());assertTrue(Arrays.stream(CNonceAdmissionStore.class.getMethods()).noneMatch(m->m.getName().toLowerCase(Locale.ROOT).contains("release")||m.getName().toLowerCase(Locale.ROOT).contains("cancel")));}
    static DataSource fault(DataSource ds,String mode){var armed=new AtomicBoolean();var fired=new AtomicBoolean();return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{Object value=CConsentStoreTest.invoke(m,ds,a);if(!m.getName().equals("getConnection"))return value;Connection c=(Connection)value;return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p2,m2,a2)->{
        if(m2.getName().equals("commit")&&armed.get()&&!mode.equals("insert")&&fired.compareAndSet(false,true)){if(mode.equals("after"))c.commit();throw new SQLException("private ambiguous transaction detail","08006");}
        Object result=CConsentStoreTest.invoke(m2,c,a2);if(m2.getName().equals("prepareStatement")&&a2[0].toString().startsWith("INSERT INTO tms_c_execution.nonce_admissions")){armed.set(true);if(mode.equals("insert")){PreparedStatement ps=(PreparedStatement)result;return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p3,m3,a3)->{if(m3.getName().equals("executeUpdate")&&fired.compareAndSet(false,true))throw new SQLException("private insert detail","40001");return CConsentStoreTest.invoke(m3,ps,a3);});}}return result;});});}
}
