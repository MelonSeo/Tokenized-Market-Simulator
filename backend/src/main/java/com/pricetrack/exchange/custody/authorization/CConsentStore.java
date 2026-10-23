package com.pricetrack.exchange.custody.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.custody.authorization.CConsentModels.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import static com.pricetrack.exchange.custody.authorization.CConsentModels.*;

/** Records independently signed C consent, NOT PREPARED/executable operations. No RPC or asset writes. */
public final class CConsentStore {
    private final DataSource source;private final CCustodyIdentity identity;private final Clock clock;
    private final ObjectMapper json=new ObjectMapper();
    public CConsentStore(DataSource source,CCustodyIdentity identity,Clock clock){this.source=Objects.requireNonNull(source);this.identity=Objects.requireNonNull(identity);this.clock=Objects.requireNonNull(clock);}
    /** Explicit fresh owner installation only. Never auto-invoked by Spring. */
    public void installSchema(){
        try(var in=CConsentStore.class.getResourceAsStream("/c-consent-schema.sql")){
            if(in==null)throw fail("SCHEMA",503);byte[] bytes=in.readNBytes(32769);if(bytes.length>32768)throw fail("SCHEMA",503);
            tx(c->{if(!"PostgreSQL".equals(c.getMetaData().getDatabaseProductName()))throw fail("SCHEMA_REQUIRES_POSTGRES",503);
                try(PreparedStatement p=statement(c,"SELECT schema_name FROM information_schema.schemata WHERE schema_name='tms_c_execution'");ResultSet r=p.executeQuery()){if(r.next())throw fail("SCHEMA_ALREADY_EXISTS",409);}
                try(Statement s=c.createStatement()){s.setQueryTimeout(3);s.execute(new String(bytes,StandardCharsets.UTF_8));}return null;});
        }catch(java.io.IOException e){throw fail("SCHEMA",503);}
    }
    public Draft createDraft(long uid,String key,Intent intent){
        user(uid);String kh=keyHash(key);if(intent==null)throw fail("INVALID_INTENT",400);
        String dg=digest(identity,intent),request=requestHash(identity,uid,dg);
        return tx(c->{String owner=ownerLock(c,uid);Optional<Draft> prior=byKey(c,uid,kh);
            if(prior.isPresent()){Draft d=prior.get();if(!d.intent().equals(intent)||!d.intentDigest().equals(dg)||!d.requestHash().equals(request)||!d.intent().owner().equals(owner))throw fail("IDEMPOTENCY_CONFLICT",409);return d;}
            Instant created=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            validate(identity,intent,created);if(!owner.equals(intent.owner()))throw fail("OWNER",403);
            try(PreparedStatement s=statement(c,"INSERT INTO tms_c_execution.drafts(deployment_id,operation_id,user_id,owner,key_hash,request_hash,intent_digest,intent_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)")){
                s.setString(1,identity.deploymentId());s.setString(2,intent.operationId());s.setLong(3,uid);s.setString(4,owner);s.setString(5,kh);s.setString(6,request);s.setString(7,dg);s.setString(8,write(intent));s.setTimestamp(9,Timestamp.from(created));s.executeUpdate();}
            return new Draft(identity.deploymentId(),uid,kh,request,intent,dg,created);});
    }
    public Optional<Draft> findByKey(long uid,String key){user(uid);String kh=keyHash(key);return read(c->byKey(c,uid,kh));}
    public Draft findDraft(long uid,String operationId){user(uid);String op=operation(operationId);return read(c->draft(c,uid,op));}
    public Optional<Consent> findConsent(long uid,String operationId){user(uid);String op=operation(operationId);return read(c->{draft(c,uid,op);return consent(c,uid,op).map(Saved::value);});}
    public Consent acceptConsent(long uid,String operationId,String expectedDigest,String signature){
        user(uid);String op=operation(operationId),expected;
        try{expected=CAbiModels.hash(expectedDigest);}catch(RuntimeException invalid){throw fail("INVALID_DIGEST",400);}
        Draft original=findDraft(uid,op);String normalized;
        try{normalized=CAbiModels.signature(signature);new CEip712Verifier().verifyIntent(original.intent(),identity.chainId(),identity.controller().address(),original.intent().owner(),normalized);}
        catch(RuntimeException invalid){throw fail("INVALID_SIGNATURE",400);}
        if(!expected.equals(original.intentDigest()))throw fail("DIGEST_CONFLICT",409);
        final String sig=normalized;
        return tx(c->{String wallet=ownerLock(c,uid);Draft d=draft(c,uid,op);
            if(!wallet.equals(d.intent().owner())||!d.equals(original)||!digest(identity,d.intent()).equals(expected))throw fail("SCOPE_CONFLICT",409);
            Optional<Saved> prior=consent(c,uid,op);
            // Exact replay is historical observation, including after expiry; never renewed approval.
            if(prior.isPresent()){if(!prior.get().signature().equals(sig)||!prior.get().value().intentDigest().equals(expected))throw fail("IDEMPOTENCY_CONFLICT",409);return prior.get().value();}
            Instant recorded=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);validate(identity,d.intent(),recorded);
            try(PreparedStatement s=statement(c,"INSERT INTO tms_c_execution.consents(deployment_id,operation_id,user_id,owner,intent_digest,user_signature,recorded_at) VALUES(?,?,?,?,?,?,?)")){
                s.setString(1,identity.deploymentId());s.setString(2,op);s.setLong(3,uid);s.setString(4,wallet);s.setString(5,expected);s.setString(6,sig);s.setTimestamp(7,Timestamp.from(recorded));s.executeUpdate();}
            return new Consent(op,expected,recorded);});
    }
    private record Saved(Consent value,String signature){@Override public String toString(){return "CConsentPrivateRecord";}}
    private Optional<Saved> consent(Connection c,long uid,String op)throws SQLException{
        try(PreparedStatement s=statement(c,"SELECT intent_digest,recorded_at,user_signature FROM tms_c_execution.consents WHERE deployment_id=? AND operation_id=? AND user_id=?")){
            s.setString(1,identity.deploymentId());s.setString(2,op);s.setLong(3,uid);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(new Saved(new Consent(op,r.getString(1),r.getTimestamp(2).toInstant()),r.getString(3))):Optional.empty();}}
    }
    private Optional<Draft> byKey(Connection c,long uid,String kh)throws SQLException{
        try(PreparedStatement s=statement(c,"SELECT operation_id FROM tms_c_execution.drafts WHERE deployment_id=? AND user_id=? AND key_hash=?")){
            s.setString(1,identity.deploymentId());s.setLong(2,uid);s.setString(3,kh);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(draft(c,uid,r.getString(1))):Optional.empty();}}
    }
    private Draft draft(Connection c,long uid,String op)throws SQLException{
        try(PreparedStatement s=statement(c,"SELECT * FROM tms_c_execution.drafts WHERE deployment_id=? AND operation_id=? AND user_id=?")){
            s.setString(1,identity.deploymentId());s.setString(2,op);s.setLong(3,uid);try(ResultSet r=s.executeQuery()){
                if(!r.next())throw fail("NOT_FOUND",404);Intent i=parse(r.getString("intent_json"));String dg=digest(identity,i);
                if(!i.operationId().equals(op)||!i.owner().equals(r.getString("owner"))||!dg.equals(r.getString("intent_digest"))||!requestHash(identity,uid,dg).equals(r.getString("request_hash")))throw fail("STORED_SCOPE",503);
                return new Draft(identity.deploymentId(),uid,r.getString("key_hash"),r.getString("request_hash"),i,dg,r.getTimestamp("created_at").toInstant());}}
    }
    private String ownerLock(Connection c,long uid)throws SQLException{
        try(PreparedStatement s=statement(c,"SELECT id FROM public.users WHERE id=? FOR UPDATE")){s.setLong(1,uid);try(ResultSet r=s.executeQuery()){if(!r.next())throw fail("USER_NOT_FOUND",404);}}
        try(PreparedStatement s=statement(c,"SELECT wallet FROM tms_c_identity.wallet_links WHERE deployment_id=? AND user_id=?")){s.setString(1,identity.deploymentId());s.setLong(2,uid);try(ResultSet r=s.executeQuery()){if(!r.next())throw fail("WALLET_NOT_LINKED",409);return CCustodyIdentity.address(r.getString(1));}}
    }
    private Intent parse(String value){try{return json.readValue(value,Intent.class);}catch(Exception invalid){throw fail("STORED_SCOPE",503);}}
    private String write(Intent value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw fail("ENCODING",503);}}
    private static void user(long uid){if(uid<=0)throw fail("USER_REQUIRED",401);}
    private static String operation(String op){try{return CAbiModels.hash(op);}catch(RuntimeException invalid){throw fail("INVALID_OPERATION",400);}}
    private PreparedStatement statement(Connection c,String sql)throws SQLException{PreparedStatement s=c.prepareStatement(sql);s.setQueryTimeout(3);return s;}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Work<T> work){try(Connection c=source.getConnection()){return work.run(c);}catch(SQLException e){throw sql(e);}}
    private <T>T tx(Work<T> work){
        try(Connection c=source.getConnection()){c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try{if("PostgreSQL".equals(c.getMetaData().getDatabaseProductName()))try(Statement s=c.createStatement()){s.setQueryTimeout(3);s.execute("SET LOCAL lock_timeout='2s'");}
                T value=work.run(c);try{c.commit();}catch(SQLException uncertain){throw fail("COMMIT_UNKNOWN",503);}return value;
            }catch(RuntimeException|SQLException failure){try{c.rollback();}catch(SQLException ignored){}if(failure instanceof CCustodyFailure safe)throw safe;if(failure instanceof SQLException db)throw sql(db);throw fail("STORAGE",503);}
        }catch(SQLException e){throw sql(e);}
    }
    private static CCustodyFailure sql(SQLException e){return "23505".equals(e.getSQLState())?fail("IDEMPOTENCY_CONFLICT",409):fail("STORAGE",503);}
}
