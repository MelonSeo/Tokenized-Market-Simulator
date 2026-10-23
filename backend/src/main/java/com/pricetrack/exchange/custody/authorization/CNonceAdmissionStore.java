package com.pricetrack.exchange.custody.authorization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.custody.runtime.CCustodyRpcReader.*;
import com.pricetrack.exchange.custody.authorization.CNonceAdmissionModels.Admission;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;

/** No Report, raw transaction, broadcast, asset/house reservation or slot release is implemented here. */
public final class CNonceAdmissionStore {
    private final DataSource source;private final CCustodyIdentity identity;private final Clock clock;
    private final Function<Intent,Observation<AdmissionPrestate>> observe;
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    public CNonceAdmissionStore(DataSource source,CCustodyRpcReader reader,Clock clock){
        this(source,Objects.requireNonNull(reader).identity(),clock,reader::admissionPrestate);
    }
    /** Package-private offline test seam; production callers cannot supply an observation. */
    CNonceAdmissionStore(DataSource source,CCustodyIdentity identity,Clock clock,Function<Intent,Observation<AdmissionPrestate>> observe){
        this.source=Objects.requireNonNull(source);this.identity=Objects.requireNonNull(identity);this.clock=Objects.requireNonNull(clock);this.observe=Objects.requireNonNull(observe);
    }
    /** Explicit new owner installation, never invoked automatically by Spring. */
    public void installSchema(){
        try(var in=CNonceAdmissionStore.class.getResourceAsStream("/c-nonce-admission-schema.sql")){
            if(in==null)throw fail("SCHEMA",503);byte[] bytes=in.readNBytes(32769);if(bytes.length>32768)throw fail("SCHEMA",503);
            tx(c->{if(!"PostgreSQL".equals(c.getMetaData().getDatabaseProductName()))throw fail("SCHEMA_REQUIRES_POSTGRES",503);
                try(var s=statement(c,"SELECT table_name FROM information_schema.tables WHERE table_schema='tms_c_execution' AND table_name='nonce_admissions'");var r=s.executeQuery()){if(r.next())throw fail("SCHEMA_ALREADY_EXISTS",409);}
                try(var s=c.createStatement()){s.setQueryTimeout(3);s.execute(new String(bytes,StandardCharsets.UTF_8));}return null;});
        }catch(java.io.IOException e){throw fail("SCHEMA",503);}
    }
    public Optional<Admission> find(long uid,String operationId){user(uid);String op=operation(operationId);return read(c->{Saved saved=consent(c,uid,op);return prior(c,saved);});}
    public Admission admit(long uid,String operationId){
        user(uid);String op=operation(operationId);Saved original=read(c->consent(c,uid,op));
        Optional<Admission> existing=tx(c->{String owner=ownerLock(c,uid);Saved current=consent(c,uid,op);same(original,current,owner);return prior(c,current);});
        if(existing.isPresent())return existing.get(); // Historical observation, not renewed execution permission.
        Instant started=clock.instant();CConsentModels.validate(identity,original.intent(),started);
        try{new CEip712Verifier().verifyIntent(original.intent(),identity.chainId(),identity.controller().address(),original.intent().owner(),original.signature());}
        catch(RuntimeException invalid){throw fail("INVALID_SIGNATURE",503);}
        long began=System.nanoTime();
        Observation<AdmissionPrestate> observation=observe.apply(original.intent()); // No DB locks held across RPC.
        if(System.nanoTime()-began>=CCustodyRpcReader.SNAPSHOT_NANOS)throw fail("OBSERVATION_EXPIRED",409);
        return tx(c->{String owner=ownerLock(c,uid);Saved current=consent(c,uid,op);same(original,current,owner);
            Optional<Admission> prior=prior(c,current);if(prior.isPresent())return prior.get();
            Instant now=clock.instant();
            if(now.isBefore(started)||System.nanoTime()-began>=CCustodyRpcReader.SNAPSHOT_NANOS)throw fail("OBSERVATION_EXPIRED",409);
            CConsentModels.validate(identity,current.intent(),now);validateObservation(observation,current.intent(),now);
            String encoded=encode(observation),hash=CConsentModels.sha(encoded);Instant recorded=now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            try(var s=statement(c,"INSERT INTO tms_c_execution.nonce_admissions(deployment_id,operation_id,user_id,owner,user_nonce,intent_digest,observation_json,observation_hash,recorded_at) VALUES(?,?,?,?,?,?,?,?,?)")){
                s.setString(1,identity.deploymentId());s.setString(2,op);s.setLong(3,uid);s.setString(4,owner);s.setBigDecimal(5,new BigDecimal(current.intent().nonce()));s.setString(6,current.digest());s.setString(7,encoded);s.setString(8,hash);s.setTimestamp(9,Timestamp.from(recorded));s.executeUpdate();}
            return new Admission(identity.deploymentId(),uid,op,owner,current.intent().nonce().toString(),current.digest(),observation,recorded);});
    }
    private record Saved(long uid,Intent intent,String digest,String signature){@Override public String toString(){return "CPrivateConsent";}}
    private Saved consent(Connection c,long uid,String op)throws SQLException{
        try(var s=statement(c,"SELECT d.intent_json,d.intent_digest,d.owner,d.request_hash,c.intent_digest AS consent_digest,c.owner AS consent_owner,c.user_id AS consent_user,c.user_signature FROM tms_c_execution.drafts d JOIN tms_c_execution.consents c ON c.deployment_id=d.deployment_id AND c.operation_id=d.operation_id WHERE d.deployment_id=? AND d.user_id=? AND d.operation_id=?")){
            s.setString(1,identity.deploymentId());s.setLong(2,uid);s.setString(3,op);try(var r=s.executeQuery()){
                if(!r.next())throw fail("CONSENT_NOT_FOUND",404);Intent i=parseIntent(r.getString("intent_json"));String dg=CConsentModels.digest(identity,i);
                if(!i.operationId().equals(op)||!i.owner().equals(r.getString("owner"))||!dg.equals(r.getString("intent_digest"))||!dg.equals(r.getString("consent_digest"))||!i.owner().equals(r.getString("consent_owner"))||r.getLong("consent_user")!=uid||!CConsentModels.requestHash(identity,uid,dg).equals(r.getString("request_hash")))throw fail("STORED_SCOPE",503);
                return new Saved(uid,i,dg,r.getString("user_signature"));}}
    }
    private Optional<Admission> prior(Connection c,Saved saved)throws SQLException{
        try(var s=statement(c,"SELECT * FROM tms_c_execution.nonce_admissions WHERE deployment_id=? AND operation_id=? AND user_id=?")){
            s.setString(1,identity.deploymentId());s.setString(2,saved.intent().operationId());s.setLong(3,saved.uid());try(var r=s.executeQuery()){
                if(!r.next())return Optional.empty();String encoded=r.getString("observation_json");Observation<AdmissionPrestate> o=parseObservation(encoded);
                if(o==null||o.value()==null||o.block()==null||o.observedAt()==null||!CConsentModels.sha(encoded).equals(r.getString("observation_hash"))||!saved.digest().equals(r.getString("intent_digest"))||!saved.intent().owner().equals(r.getString("owner"))||!saved.intent().nonce().equals(r.getBigDecimal("user_nonce").toBigIntegerExact())||!identity.deploymentId().equals(o.deploymentId())||!saved.intent().equals(o.value().intent())||!saved.intent().nonce().toString().equals(o.value().userNonce()))throw fail("STORED_SCOPE",503);
                return Optional.of(new Admission(identity.deploymentId(),saved.uid(),saved.intent().operationId(),saved.intent().owner(),saved.intent().nonce().toString(),saved.digest(),o,r.getTimestamp("recorded_at").toInstant()));}}
    }
    private void validateObservation(Observation<AdmissionPrestate> o,Intent intent,Instant now){
        if(o==null||o.value()==null||o.block()==null||o.observedAt()==null||!identity.deploymentId().equals(o.deploymentId())||!intent.equals(o.value().intent())||!intent.nonce().toString().equals(o.value().userNonce())||intent.nonce().equals(CAbiModels.UINT_MAX))throw fail("OBSERVATION_SCOPE",409);
        if(o.observedAt().isAfter(now)||Duration.between(o.observedAt(),now).compareTo(Duration.ofNanos(CCustodyRpcReader.SNAPSHOT_NANOS))>=0)throw fail("OBSERVATION_EXPIRED",409);
        try{CCustodyIdentity.uint(o.block().number());CCustodyIdentity.uint(o.block().timestamp());CCustodyIdentity.hash(o.block().hash());CCustodyIdentity.hash(o.block().parentHash());CConsentModels.validate(identity,intent,Instant.ofEpochSecond(Long.parseLong(o.block().timestamp())));}
        catch(RuntimeException invalid){throw fail("OBSERVATION_SCOPE",409);}
    }
    private static void same(Saved original,Saved current,String wallet){if(!original.equals(current)||!wallet.equals(current.intent().owner()))throw fail("SCOPE_CONFLICT",409);}
    private String ownerLock(Connection c,long uid)throws SQLException{
        try(var s=statement(c,"SELECT id FROM public.users WHERE id=? FOR UPDATE")){s.setLong(1,uid);try(var r=s.executeQuery()){if(!r.next())throw fail("USER_NOT_FOUND",404);}}
        try(var s=statement(c,"SELECT wallet FROM tms_c_identity.wallet_links WHERE deployment_id=? AND user_id=?")){s.setString(1,identity.deploymentId());s.setLong(2,uid);try(var r=s.executeQuery()){if(!r.next())throw fail("WALLET_NOT_LINKED",409);return CCustodyIdentity.address(r.getString(1));}}
    }
    private String encode(Observation<AdmissionPrestate> value){try{String v=json.writeValueAsString(value);if(v.length()>32768)throw fail("ENCODING",503);return v;}catch(Exception e){throw fail("ENCODING",503);}}
    private Intent parseIntent(String value){try{return json.readValue(value,Intent.class);}catch(Exception e){throw fail("STORED_SCOPE",503);}}
    private Observation<AdmissionPrestate> parseObservation(String value){try{return json.readerFor(new TypeReference<Observation<AdmissionPrestate>>(){}).without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(value);}catch(Exception e){throw fail("STORED_SCOPE",503);}}
    private static String operation(String value){try{return CAbiModels.hash(value);}catch(RuntimeException e){throw fail("INVALID_OPERATION",400);}}
    private static void user(long uid){if(uid<=0)throw fail("USER_REQUIRED",401);}
    private static CCustodyFailure fail(String suffix,int status){return new CCustodyFailure("C_NONCE_ADMISSION_"+suffix,status);}
    private PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(3);return s;}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Work<T> work){try(var c=source.getConnection()){return work.run(c);}catch(SQLException e){throw sql(e);}}
    private <T>T tx(Work<T> work){try(var c=source.getConnection()){
        c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try{if("PostgreSQL".equals(c.getMetaData().getDatabaseProductName()))try(var s=c.createStatement()){s.setQueryTimeout(3);s.execute("SET LOCAL lock_timeout='2s'");}
            T value=work.run(c);try{c.commit();}catch(SQLException uncertain){throw fail("COMMIT_UNKNOWN",503);}return value;
        }catch(RuntimeException|SQLException failure){try{c.rollback();}catch(SQLException ignored){}if(failure instanceof CCustodyFailure safe)throw safe;if(failure instanceof SQLException e)throw sql(e);throw fail("STORAGE",503);}
    }catch(SQLException e){throw sql(e);}}
    private static CCustodyFailure sql(SQLException e){return "23505".equals(e.getSQLState())?fail("NONCE_CONFLICT",409):fail("STORAGE",503);}
}
