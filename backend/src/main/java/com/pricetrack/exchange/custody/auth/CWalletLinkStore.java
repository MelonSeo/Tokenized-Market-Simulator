package com.pricetrack.exchange.custody.auth;

import com.pricetrack.exchange.custody.runtime.CCustodyFailure;
import com.pricetrack.exchange.custody.runtime.CCustodyIdentity;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;

/** C proof/link metadata only. No RPC, signer key, FuturesLedger or asset reservation. */
public final class CWalletLinkStore {
    private static final String NS="tms_c_identity.";
    private final DataSource source;private final CCustodyIdentity identity;private final Clock clock;
    public record Challenge(String id,String deploymentId,long userId,String wallet,String origin,String message,
        String messageHash,String randomNonce,Instant issuedAt,Instant expiresAt,boolean consumed){
        @Override public String toString(){return "CWalletChallenge[id="+id+",consumed="+consumed+"]";}
    }
    public record Link(String wallet,Instant linkedAt,String proofHash) {}
    public CWalletLinkStore(DataSource source,CCustodyIdentity identity,Clock clock){this.source=Objects.requireNonNull(source);this.identity=Objects.requireNonNull(identity);this.clock=Objects.requireNonNull(clock);}
    /** Never invoked by Spring configuration. The new-fixture owner must install explicitly. */
    public void installSchema(){
        try(var in=CWalletLinkStore.class.getResourceAsStream("/c-wallet-schema.sql")){
            if(in==null)throw fail("SCHEMA_MISSING",503);byte[] bytes=in.readNBytes(16_385);if(bytes.length>16_384)throw fail("SCHEMA_SIZE",503);
            String sql=new String(bytes,StandardCharsets.UTF_8);transaction(c->{try(Statement s=c.createStatement()){s.setQueryTimeout(3);s.execute(sql);}return null;});
        }catch(java.io.IOException e){throw fail("SCHEMA_READ",503);}
    }
    public Optional<Link> find(long uid){user(uid);return read(c->find(c,uid));}
    public Challenge create(Challenge proof){
        validate(proof);return transaction(c->{userLock(c,proof.userId());if(find(c,proof.userId()).isPresent())throw fail("ALREADY_LINKED",409);
            try(PreparedStatement s=statement(c,"UPDATE "+NS+"wallet_challenges SET consumed=TRUE WHERE deployment_id=? AND user_id=? AND consumed=FALSE")){
                s.setString(1,identity.deploymentId());s.setLong(2,proof.userId());s.executeUpdate();}
            try(PreparedStatement s=statement(c,"INSERT INTO "+NS+"wallet_challenges(id,deployment_id,user_id,wallet,origin,message,message_hash,random_nonce,issued_at,expires_at,consumed) VALUES(?,?,?,?,?,?,?,?,?,?,FALSE)")){
                s.setString(1,proof.id());s.setString(2,proof.deploymentId());s.setLong(3,proof.userId());s.setString(4,proof.wallet());s.setString(5,proof.origin());s.setString(6,proof.message());s.setString(7,proof.messageHash());s.setString(8,proof.randomNonce());s.setTimestamp(9,Timestamp.from(proof.issuedAt()));s.setTimestamp(10,Timestamp.from(proof.expiresAt()));s.executeUpdate();}
            return proof;});
    }
    public Challenge challenge(long uid,String id){user(uid);id(id);return read(c->challenge(c,uid,id,false));}
    /** Signature was checked outside DB locks; exact original hash/scope is rechecked under the lock. */
    public Link link(long uid,String id,String expectedMessageHash,String signature,Instant now){
        user(uid);id(id);if(expectedMessageHash==null||!expectedMessageHash.matches("[0-9a-f]{64}")||signature==null||!signature.matches("0x[0-9a-fA-F]{130}")||now==null)throw fail("INVALID_PROOF",400);
        // Cryptographic work is outside row locks; commit rechecks the exact original proof.
        Challenge original=challenge(uid,id);validate(original);
        try{byte[] digest=org.web3j.crypto.Sign.getEthereumMessageHash(original.message().getBytes(StandardCharsets.UTF_8));
            String signer=new CEip712Verifier().recover("0x"+HexFormat.of().formatHex(digest),signature);
            if(!signer.equals(original.wallet()))throw fail("INVALID_SIGNATURE",400);
        }catch(RuntimeException invalid){throw fail("INVALID_SIGNATURE",400);}
        return transaction(c->{userLock(c,uid);Challenge p=challenge(c,uid,id,true);validate(p);
            Instant acceptedAt=clock.instant();
            if(p.consumed()||acceptedAt.isBefore(p.issuedAt())||!acceptedAt.isBefore(p.expiresAt()))throw fail("EXPIRED_OR_USED",409);
            if(!p.messageHash().equals(expectedMessageHash)||!p.messageHash().equals(original.messageHash()))throw fail("PROOF_CHANGED",409);
            if(find(c,uid).isPresent())throw fail("ALREADY_LINKED",409);
            try(PreparedStatement s=statement(c,"INSERT INTO "+NS+"wallet_links(deployment_id,user_id,wallet,challenge_id,proof_hash,linked_at) VALUES(?,?,?,?,?,?)")){
                s.setString(1,identity.deploymentId());s.setLong(2,uid);s.setString(3,p.wallet());s.setString(4,p.id());s.setString(5,p.messageHash());s.setTimestamp(6,Timestamp.from(acceptedAt));s.executeUpdate();}
            try(PreparedStatement s=statement(c,"UPDATE "+NS+"wallet_challenges SET consumed=TRUE,proof_signature=? WHERE id=? AND deployment_id=? AND user_id=? AND consumed=FALSE")){
                s.setString(1,signature);s.setString(2,id);s.setString(3,identity.deploymentId());s.setLong(4,uid);if(s.executeUpdate()!=1)throw fail("PROOF_CHANGED",409);}
            return new Link(p.wallet(),acceptedAt,p.messageHash());});
    }
    private Optional<Link> find(Connection c,long uid)throws SQLException{
        try(PreparedStatement s=statement(c,"SELECT wallet,linked_at,proof_hash FROM "+NS+"wallet_links WHERE deployment_id=? AND user_id=?")){
            s.setString(1,identity.deploymentId());s.setLong(2,uid);try(ResultSet r=s.executeQuery()){if(!r.next())return Optional.empty();return Optional.of(new Link(CCustodyIdentity.address(r.getString(1)),r.getTimestamp(2).toInstant(),r.getString(3)));}}
    }
    private Challenge challenge(Connection c,long uid,String id,boolean lock)throws SQLException{
        try(PreparedStatement s=statement(c,"SELECT * FROM "+NS+"wallet_challenges WHERE id=? AND deployment_id=? AND user_id=?"+(lock?" FOR UPDATE":""))){
            s.setString(1,id);s.setString(2,identity.deploymentId());s.setLong(3,uid);try(ResultSet r=s.executeQuery()){
                if(!r.next())throw fail("CHALLENGE_NOT_FOUND",404);return new Challenge(r.getString("id"),r.getString("deployment_id"),r.getLong("user_id"),r.getString("wallet"),r.getString("origin"),r.getString("message"),r.getString("message_hash"),r.getString("random_nonce"),r.getTimestamp("issued_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getBoolean("consumed"));}}
    }
    private void userLock(Connection c,long uid)throws SQLException{try(PreparedStatement s=statement(c,"SELECT id FROM public.users WHERE id=? FOR UPDATE")){s.setLong(1,uid);try(ResultSet r=s.executeQuery()){if(!r.next())throw fail("USER_NOT_FOUND",404);}}}
    private void validate(Challenge p){
        if(p==null)throw fail("INVALID_PROOF",400);user(p.userId());id(p.id());String wallet=CCustodyIdentity.address(p.wallet());
        if(p.deploymentId()==null||!p.deploymentId().equals(identity.deploymentId())||!identity.origin().equals(p.origin())||!p.wallet().equals(wallet)||p.message()==null||p.message().length()>2048||!digest(p.message()).equals(p.messageHash())||p.randomNonce()==null||!p.randomNonce().matches("[0-9a-f]{64}")||p.issuedAt()==null||p.expiresAt()==null||p.issuedAt().getNano()%1_000_000!=0||!p.expiresAt().equals(p.issuedAt().plusSeconds(120))||!p.message().equals(CWalletProofService.message(identity,p.id(),p.userId(),wallet,p.randomNonce(),p.issuedAt(),p.expiresAt())))throw fail("INVALID_PROOF_SCOPE",400);
    }
    private PreparedStatement statement(Connection c,String sql)throws SQLException{PreparedStatement s=c.prepareStatement(sql);s.setQueryTimeout(3);return s;}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Work<T> work){try(Connection c=source.getConnection()){return work.run(c);}catch(SQLException e){throw sql(e);}}
    private <T>T transaction(Work<T> work){
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try{
                if(c.getMetaData().getDatabaseProductName().equals("PostgreSQL"))try(Statement s=c.createStatement()){s.setQueryTimeout(3);s.execute("SET LOCAL lock_timeout='2s'");}
                T value=work.run(c);
                try{c.commit();}catch(SQLException unknown){throw fail("COMMIT_UNKNOWN",503);}
                return value;
            }catch(RuntimeException|SQLException failure){try{c.rollback();}catch(SQLException ignored){}if(failure instanceof CCustodyFailure safe)throw safe;if(failure instanceof SQLException sql)throw sql(sql);throw fail("STORAGE",503);}
        }catch(SQLException e){throw sql(e);}
    }
    public static String digest(String message){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalArgumentException("C_WALLET_MESSAGE");}}
    private static void user(long uid){if(uid<=0)throw fail("USER_REQUIRED",401);}
    private static void id(String id){try{if(id==null||!UUID.fromString(id).toString().equals(id))throw fail("INVALID_ID",400);}catch(RuntimeException invalid){throw fail("INVALID_ID",400);}}
    private static CCustodyFailure sql(SQLException e){return "23505".equals(e.getSQLState())?fail("ALREADY_LINKED",409):fail("STORAGE",503);}
    private static CCustodyFailure fail(String suffix,int status){return new CCustodyFailure("C_WALLET_"+suffix,status);}
}
