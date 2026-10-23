package com.pricetrack.exchange.custody.direct;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pricetrack.exchange.custody.abi.*;
import com.pricetrack.exchange.custody.abi.CAbiModels.*;
import com.pricetrack.exchange.custody.authorization.CConsentModels;
import com.pricetrack.exchange.custody.direct.CDirectModels.Execution;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Durable once-only Oracle request claim and immutable response. No raw, broadcast, relayer nonce or asset writes. */
public final class CDirectExecutionStore {
    private final DataSource source;private final CCustodyIdentity identity;private final Clock clock;
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    public CDirectExecutionStore(DataSource source,CCustodyIdentity identity,Clock clock){this.source=Objects.requireNonNull(source);this.identity=Objects.requireNonNull(identity);this.clock=Objects.requireNonNull(clock);}
    public void installSchema(){
        try(var in=CDirectExecutionStore.class.getResourceAsStream("/c-direct-execution-schema.sql")){
            if(in==null)throw fail("SCHEMA",503);byte[] sql=in.readNBytes(32769);if(sql.length>32768)throw fail("SCHEMA",503);
            tx(c->{if(!"PostgreSQL".equals(c.getMetaData().getDatabaseProductName()))throw fail("POSTGRES_REQUIRED",503);
                try(var s=statement(c,"SELECT table_name FROM information_schema.tables WHERE table_schema='tms_c_execution' AND table_name IN ('direct_execution_claims','direct_executions')");var r=s.executeQuery()){if(r.next())throw fail("SCHEMA_EXISTS",409);}
                try(var s=c.createStatement()){s.setQueryTimeout(3);s.execute(new String(sql,StandardCharsets.UTF_8));}return null;});
        }catch(java.io.IOException e){throw fail("SCHEMA",503);}
    }
    public Optional<Execution> find(long uid,String op){user(uid);op=operation(op);String id=op;return read(c->{Intent i=draft(c,uid,id);return execution(c,uid,i);});}
    /** false means the request is already claimed; absence of READY must never trigger another Oracle request. */
    public boolean claim(long uid,String op){user(uid);String id=operation(op);return tx(c->{String owner=ownerLock(c,uid);Intent i=draft(c,uid,id);if(!owner.equals(i.owner()))throw fail("OWNER",403);
        try(var s=statement(c,"SELECT operation_id FROM tms_c_execution.direct_execution_claims WHERE deployment_id=? AND operation_id=?")){s.setString(1,identity.deploymentId());s.setString(2,id);try(var r=s.executeQuery()){if(r.next())return false;}}
        CConsentModels.validate(identity,i,clock.instant());
        try(var s=statement(c,"INSERT INTO tms_c_execution.direct_execution_claims(deployment_id,operation_id,user_id,owner,intent_digest,claimed_at) VALUES(?,?,?,?,?,?)")){s.setString(1,identity.deploymentId());s.setString(2,id);s.setLong(3,uid);s.setString(4,owner);s.setString(5,CConsentModels.digest(identity,i));s.setTimestamp(6,Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)));s.executeUpdate();}return true;});}
    public Execution save(long uid,Execution e){user(uid);if(e==null||e.intent()==null)throw fail("EXECUTION",503);validate(e);
        return tx(c->{String owner=ownerLock(c,uid);Intent i=draft(c,uid,e.intent().operationId());if(!i.equals(e.intent())||!owner.equals(i.owner()))throw fail("SCOPE",409);
            // ownerLock already serializes claim/save for this user. Claims are append-only;
            // locking them again would require PostgreSQL UPDATE privilege unnecessarily.
            try(var s=statement(c,"SELECT user_id,owner,intent_digest FROM tms_c_execution.direct_execution_claims WHERE deployment_id=? AND operation_id=?")){s.setString(1,identity.deploymentId());s.setString(2,i.operationId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(1)!=uid||!owner.equals(r.getString(2))||!CConsentModels.digest(identity,i).equals(r.getString(3)))throw fail("CLAIM_REQUIRED",409);}}
            Optional<Execution> old=execution(c,uid,i);if(old.isPresent()){if(!old.get().equals(e))throw fail("IMMUTABLE_CONFLICT",409);return old.get();}
            Instant now=clock.instant();CConsentModels.validate(identity,i,now);Duration age=Duration.between(e.market().observedAt(),now);
            if(age.compareTo(Duration.ofSeconds(5))>0||age.compareTo(Duration.ofSeconds(-2))<0||java.math.BigInteger.valueOf(now.getEpochSecond()).compareTo(e.report().validUntil())>0)throw fail("EXECUTION_EXPIRED",409);
            String encoded=encode(e);try(var s=statement(c,"INSERT INTO tms_c_execution.direct_executions(deployment_id,operation_id,user_id,owner,intent_digest,execution_json,execution_hash,recorded_at) VALUES(?,?,?,?,?,?,?,?)")){s.setString(1,identity.deploymentId());s.setString(2,i.operationId());s.setLong(3,uid);s.setString(4,owner);s.setString(5,CConsentModels.digest(identity,i));s.setString(6,encoded);s.setString(7,sha(encoded));s.setTimestamp(8,Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)));s.executeUpdate();}return e;});
    }
    public String calldata(Execution e){validate(e);return new CAbiCodec().encode(new Direct(e.report(),e.priceSignature(),e.intent()));}
    private Optional<Execution> execution(Connection c,long uid,Intent i)throws SQLException{
        try(var s=statement(c,"SELECT owner,intent_digest,execution_json,execution_hash FROM tms_c_execution.direct_executions WHERE deployment_id=? AND operation_id=? AND user_id=?")){s.setString(1,identity.deploymentId());s.setString(2,i.operationId());s.setLong(3,uid);try(var r=s.executeQuery()){if(!r.next())return Optional.empty();String encoded=r.getString(3);if(!sha(encoded).equals(r.getString(4))||!i.owner().equals(r.getString(1))||!CConsentModels.digest(identity,i).equals(r.getString(2)))throw fail("STORED_SCOPE",503);Execution e=parse(encoded);if(!i.equals(e.intent()))throw fail("STORED_SCOPE",503);validate(e);return Optional.of(e);}}
    }
    private Intent draft(Connection c,long uid,String op)throws SQLException{
        try(var s=statement(c,"SELECT owner,intent_digest,intent_json,request_hash FROM tms_c_execution.drafts WHERE deployment_id=? AND operation_id=? AND user_id=?")){s.setString(1,identity.deploymentId());s.setString(2,op);s.setLong(3,uid);try(var r=s.executeQuery()){if(!r.next())throw fail("NOT_FOUND",404);Intent i;try{i=json.readValue(r.getString(3),Intent.class);}catch(Exception e){throw fail("STORED_SCOPE",503);}String digest=CConsentModels.digest(identity,i);if(!i.operationId().equals(op)||!i.owner().equals(r.getString(1))||!digest.equals(r.getString(2))||!CConsentModels.requestHash(identity,uid,digest).equals(r.getString(4)))throw fail("STORED_SCOPE",503);return i;}}
    }
    private void validate(Execution e){
        try{if(e==null||e.intent()==null||e.report()==null||e.market()==null||e.market().observedAt()==null||e.market().price()==null||e.market().provider()==null||e.market().provider().isBlank())throw fail("STORED_SCOPE",503);
            Intent i=e.intent();Report r=e.report();if(!Set.of(java.math.BigInteger.ZERO,java.math.BigInteger.valueOf(4)).contains(i.action())
                ||!i.relayer().equals(identity.relayer())||!i.symbolHash().equals(identity.symbolHash())||!i.policyHash().equals(identity.policyHash())||!i.accountRef().equals(CConsentModels.accountRef(i.owner(),identity.symbolHash()))
                ||!r.operationId().equals(i.operationId())||!r.positionId().equals(i.positionId())||!r.accountRef().equals(i.accountRef())||!r.symbolHash().equals(i.symbolHash())||!r.action().equals(i.action())
                ||!r.expectedVersion().equals(i.expectedVersion())||!r.expectedCallEpoch().equals(i.expectedCallEpoch())||!r.side().equals(i.side())||!r.leverage().equals(i.leverage())||!r.collateralBefore().equals(i.collateralBefore())||!r.collateralAfter().equals(i.collateralAfter())
                ||!r.policyHash().equals(i.policyHash())||!r.executor().equals(identity.controller().address())||!r.validUntil().equals(r.observedAt().add(java.math.BigInteger.valueOf(30)))
                ||!r.priceE8().equals(e.market().price())||r.observedAt().longValueExact()!=e.market().observedAt().getEpochSecond()
                ||r.priceE8().compareTo(i.priceMinE8())<0||r.priceE8().compareTo(i.priceMaxE8())>0||r.quantity().compareTo(i.quantityMin())<0||r.quantity().compareTo(i.quantityMax())>0)throw fail("STORED_SCOPE",503);
            if(i.action().signum()==0){if(i.entryPriceE8().signum()!=0||!r.entryPriceE8().equals(r.priceE8())||!r.quantity().equals(FuturesMath.quantity(i.collateralAfter(),i.leverage().intValueExact(),r.priceE8())))throw fail("STORED_SCOPE",503);}
            else if(!i.quantityMin().equals(i.quantityMax())||!r.quantity().equals(i.quantityMin())||!r.entryPriceE8().equals(i.entryPriceE8()))throw fail("STORED_SCOPE",503);
            var verifier=new CEip712Verifier();if(!verifier.reportDigest(r,identity.chainId(),identity.rules().address()).equals(e.reportDigest()))throw fail("STORED_SCOPE",503);verifier.verifyReport(r,identity.chainId(),identity.rules().address(),identity.oracle(),e.priceSignature());
        }catch(CCustodyFailure safe){throw safe;}catch(RuntimeException invalid){throw fail("STORED_SCOPE",503);}
    }
    private String ownerLock(Connection c,long uid)throws SQLException{try(var s=statement(c,"SELECT id FROM public.users WHERE id=? FOR UPDATE")){s.setLong(1,uid);try(var r=s.executeQuery()){if(!r.next())throw fail("USER_NOT_FOUND",404);}}try(var s=statement(c,"SELECT wallet FROM tms_c_identity.wallet_links WHERE deployment_id=? AND user_id=?")){s.setString(1,identity.deploymentId());s.setLong(2,uid);try(var r=s.executeQuery()){if(!r.next())throw fail("WALLET_REQUIRED",403);return CCustodyIdentity.address(r.getString(1));}}}
    private String encode(Execution e){try{String value=json.writeValueAsString(e);if(value.length()>32768)throw fail("ENCODING",503);return value;}catch(Exception ex){throw fail("ENCODING",503);}}
    private Execution parse(String value){try{return json.readValue(value,Execution.class);}catch(Exception e){throw fail("STORED_SCOPE",503);}}
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw fail("HASH",503);}}
    private static void user(long uid){if(uid<=0)throw fail("AUTHENTICATION_REQUIRED",401);}
    private static String operation(String op){try{return CAbiModels.hash(op);}catch(RuntimeException e){throw fail("INVALID_ID",400);}}
    private PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(3);return s;}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Work<T> work){try(var c=source.getConnection()){return work.run(c);}catch(SQLException e){throw fail("STORAGE",503);}}
    private <T>T tx(Work<T> work){try(var c=source.getConnection()){c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);try{
        if("PostgreSQL".equals(c.getMetaData().getDatabaseProductName()))try(var s=c.createStatement()){s.setQueryTimeout(3);s.execute("SET LOCAL lock_timeout='2s'");}
        T v=work.run(c);try{c.commit();}catch(SQLException e){throw fail("COMMIT_UNKNOWN",503);}return v;
    }catch(RuntimeException|SQLException e){try{c.rollback();}catch(SQLException ignored){}if(e instanceof CCustodyFailure safe)throw safe;throw fail("STORAGE",503);}}catch(SQLException e){throw fail("STORAGE",503);}}
    static CCustodyFailure fail(String code,int status){return new CCustodyFailure("C_DIRECT_"+code,status);}
}
