package com.pricetrack.exchange.futures.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.runtime.FuturesMarketGate.Mark;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.*;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions only. No RPC/signing while locking user, wallet, draft, position or balance. */
public class AuthorizedFuturesStore {
    private final JdbcTemplate jdbc;private final FuturesLedger ledger;private final ObjectMapper json;
    public AuthorizedFuturesStore(DataSource ds,FuturesLedger ledger,ObjectMapper json){jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(3);this.ledger=ledger;this.json=json;}
    public record Challenge(String id,String wallet,String message,Instant expiresAt) {}
    private record Proof(String wallet,String message,Instant expires,boolean used) {}
    public record Draft(String id,long userId,String position,String operation,String key,FuturesReport report,String priceSignature,Authorization authorization,Mark mark,Instant expiresAt,String state) {
        @Override public String toString(){return "AuthorizedDraft[id="+id+",state="+state+"]";}
    }
    private void userLock(long uid){if(jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,uid).isEmpty())throw new Failure("USER_NOT_FOUND");}
    public String wallet(long uid){return jdbc.queryForList("SELECT wallet FROM futures_wallet_links WHERE user_id=?",String.class,uid).stream().findFirst().orElseThrow(()->new Failure("WALLET_NOT_FOUND"));}
    @Transactional(timeout=5)
    public Challenge challenge(long uid,String wallet,String origin,BigInteger chain,Instant now,String random){
        userLock(uid);if(!jdbc.queryForList("SELECT wallet FROM futures_wallet_links WHERE user_id=?",String.class,uid).isEmpty())throw new Failure("WALLET_ALREADY_LINKED");
        String id=UUID.randomUUID().toString();Instant expires=now.plusSeconds(120);
        String message="TMS wallet link only. NOT a trade approval.\nOrigin: "+origin+"\nAccount: "+uid+"\nWallet: "+wallet+"\nChain ID: "+chain+"\nNonce: "+random+"\nExpires: "+expires;
        jdbc.update("UPDATE futures_wallet_challenges SET consumed=TRUE WHERE user_id=? AND consumed=FALSE",uid);
        jdbc.update("INSERT INTO futures_wallet_challenges(id,user_id,wallet,message,expires_at,consumed) VALUES(?,?,?,?,?,FALSE)",id,uid,wallet,message,Timestamp.from(expires));
        return new Challenge(id,wallet,message,expires);
    }
    @Transactional(timeout=5)
    public void link(long uid,String id,String signature,Instant now){
        userLock(uid);
        var rows=jdbc.query("SELECT * FROM futures_wallet_challenges WHERE id=? AND user_id=? FOR UPDATE",(rs,n)->new Proof(rs.getString("wallet"),rs.getString("message"),rs.getTimestamp("expires_at").toInstant(),rs.getBoolean("consumed")),id,uid);
        if(rows.size()!=1)throw new Failure("WALLET_CHALLENGE_NOT_FOUND");var r=rows.get(0);
        if(r.used()||!now.isBefore(r.expires()))throw new Failure("WALLET_CHALLENGE_EXPIRED_OR_USED");
        byte[] digest=org.web3j.crypto.Sign.getEthereumMessageHash(r.message().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!UserIntentCodec.signed(digest,signature,r.wallet()))throw new Failure("WALLET_SIGNATURE_INVALID");
        if(!jdbc.queryForList("SELECT user_id FROM futures_wallet_links WHERE user_id=? OR wallet=?",Long.class,uid,r.wallet()).isEmpty())throw new Failure("WALLET_ALREADY_LINKED");
        try{jdbc.update("INSERT INTO futures_wallet_links(user_id,wallet,linked_at) VALUES(?,?,?)",uid,r.wallet(),Timestamp.from(now));}
        catch(org.springframework.dao.DataIntegrityViolationException duplicate){throw new Failure("WALLET_ALREADY_LINKED");}
        jdbc.update("UPDATE futures_wallet_challenges SET consumed=TRUE,proof_signature=? WHERE id=?",signature,id);
    }
    public Challenge challenge(long uid,String id){return jdbc.query("SELECT * FROM futures_wallet_challenges WHERE id=? AND user_id=?",(r,n)->new Challenge(r.getString("id"),r.getString("wallet"),r.getString("message"),r.getTimestamp("expires_at").toInstant()),id,uid).stream().findFirst().orElseThrow(()->new Failure("WALLET_CHALLENGE_NOT_FOUND"));}
    public Optional<Draft> byKey(long uid,String key){return jdbc.queryForList("SELECT id FROM futures_authorized_drafts WHERE user_id=? AND request_key=?",String.class,uid,FuturesLedger.fingerprint(key)).stream().findFirst().map(id->draft(uid,id,false));}
    public Draft draft(long uid,String id,boolean lock){return jdbc.query("SELECT * FROM futures_authorized_drafts WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),(r,n)->new Draft(r.getString("id"),uid,r.getString("position_id"),r.getString("operation_id"),r.getString("request_key"),read(r.getString("report_json"),FuturesReport.class),r.getString("price_signature"),read(r.getString("metadata_json"),Authorization.class),new Mark(r.getBigDecimal("price").toBigIntegerExact(),r.getTimestamp("observed_at").toInstant(),r.getString("provider"),(Long)r.getObject("generation")),r.getTimestamp("expires_at").toInstant(),r.getString("state")),id,uid).stream().findFirst().orElseThrow(()->new Failure("AUTHORIZED_DRAFT_NOT_FOUND"));}
    @Transactional(timeout=5)
    public Draft create(long uid,String key,String pid,String op,FuturesReport r,String ps,Authorization a,Mark mark,Instant now){
        ledger.lockUserIntent(uid);String linked=wallet(uid);
        if(!linked.equals(a.owner()))throw new Failure("WALLET_OWNER_CONFLICT");
        String current=jdbc.queryForObject("SELECT inflight_draft FROM futures_wallet_links WHERE user_id=? FOR UPDATE",String.class,uid);
        var replay=byKey(uid,key);
        if(replay.isPresent()){
            if(!sameRequest(replay.get().report(),r))throw new Failure("IDEMPOTENCY_CONFLICT");return replay.get();
        }
        ensureDraftAvailable(uid,current,now);
        if(r.action()!=0){var p=ledger.position(pid,uid);if(!p.status().equals("OPEN")||p.pendingOperation()!=null||p.version()!=r.expectedVersion().longValueExact()||!position(pid,uid).equals(new Authorization(a.chain(),a.owner(),null,null)))throw new Failure("AUTHORIZED_POSITION_CONFLICT");}
        if(!now.isBefore(Instant.ofEpochSecond(a.approval().deadline().longValueExact())))throw new Failure("AUTHORIZED_DRAFT_EXPIRED");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO futures_authorized_drafts VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT')",id,uid,pid,op,FuturesLedger.fingerprint(key),write(r),ps,write(a),new BigDecimal(mark.price()),Timestamp.from(mark.observedAt()),mark.provider(),mark.generation(),Timestamp.from(Instant.ofEpochSecond(a.approval().deadline().longValueExact())));
        jdbc.update("UPDATE futures_wallet_links SET inflight_draft=? WHERE user_id=?",id,uid);return draft(uid,id,false);
    }
    private boolean sameRequest(FuturesReport a,FuturesReport b){return a.action()==b.action()&&a.side()==b.side()&&a.leverage()==b.leverage()&&a.collateralAfter().subtract(a.collateralBefore()).equals(b.collateralAfter().subtract(b.collateralBefore()))&&(a.action()==0||a.positionId().equals(b.positionId())&&a.expectedVersion().equals(b.expectedVersion()));}
    /** Called only while holding the shared user and wallet locks, across both versions. */
    public void ensureDraftAvailable(long uid,String current,Instant now){
        if(current==null)return;
        var rows=jdbc.query("SELECT state,operation_id,expires_at FROM futures_authorized_drafts WHERE id=? AND user_id=? UNION ALL SELECT state,operation_id,expires_at FROM futures_authorized_v2_drafts WHERE id=? AND user_id=?",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getTimestamp(3).toInstant()},current,uid,current,uid);
        if(rows.size()!=1)throw new Failure("AUTHORIZED_DRAFT_IDENTITY_CONFLICT");var row=rows.get(0);
        if(row[0].equals("ACCEPTED")&&!Set.of("CONFIRMED","REJECTED","REVERTED").contains(ledger.operation((String)row[1]).state()))throw new Failure("WALLET_NONCE_BUSY");
        if(row[0].equals("DRAFT")&&!now.isAfter((Instant)row[2]))throw new Failure("WALLET_DRAFT_PENDING");
    }
    @Transactional(timeout=5)
    public Operation accept(long uid,String id,String key,String userSignature,Instant now){
        ledger.lockUserIntent(uid);Draft d=draft(uid,id,true);
        if(!d.key().equals(FuturesLedger.fingerprint(key)))throw new Failure("IDEMPOTENCY_CONFLICT");
        if(d.state().equals("ACCEPTED")){var stored=operation(d.operation());if(!Objects.equals(stored.userSignature(),userSignature))throw new Failure("IDEMPOTENCY_CONFLICT");return ledger.operation(d.operation());}
        if(!d.state().equals("DRAFT")||!now.isBefore(d.expiresAt()))throw new Failure("AUTHORIZED_DRAFT_EXPIRED");
        String inflight=jdbc.queryForObject("SELECT inflight_draft FROM futures_wallet_links WHERE user_id=? FOR UPDATE",String.class,uid);
        if(!id.equals(inflight)||!wallet(uid).equals(d.authorization().owner()))throw new Failure("WALLET_OWNER_CONFLICT");
        var a=new Authorization(d.authorization().chain(),d.authorization().owner(),d.authorization().approval(),userSignature);var r=d.report();
        try{UserIntentCodec.verify(r,d.priceSignature(),a);}catch(RuntimeException invalid){throw new Failure("USER_SIGNATURE_INVALID");}
        Operation op;
        if(r.action()==0){op=ledger.prepareAuthorizedOpen(uid,key,com.pricetrack.exchange.futures.math.FuturesMath.Side.values()[r.side()],r.collateralAfter(),r.leverage(),r.priceE8(),r.observedAt().longValueExact(),d.position(),d.operation(),r.accountRef());
            jdbc.update("INSERT INTO futures_authorized_positions VALUES(?,?,?)",d.position(),uid,write(new Authorization(a.chain(),a.owner(),null,null)));
        }else{op=ledger.prepareAuthorized(uid,d.position(),key,Action.values()[r.action()],r.expectedVersion().longValueExact(),r.priceE8(),r.observedAt().longValueExact(),r.collateralAfter().subtract(r.collateralBefore()),d.operation());}
        if(!op.id().equals(d.operation())||!r.equals(FuturesReport.from(op,ledger.position(op.positionId(),uid),a.chain().reportExecutor())))throw new Failure("AUTHORIZED_REPORT_CONFLICT");
        jdbc.update("INSERT INTO futures_authorized_operations VALUES(?,?,?,?)",op.id(),write(a),write(r),d.priceSignature());
        bind(op,d.mark());jdbc.update("UPDATE futures_authorized_drafts SET state='ACCEPTED' WHERE id=?",id);return op;
    }
    public Authorization position(String pid,long uid){return jdbc.queryForList("SELECT metadata_json FROM futures_authorized_positions WHERE position_id=? AND user_id=?",String.class,pid,uid).stream().findFirst().map(s->read(s,Authorization.class)).orElseThrow(()->new Failure("AUTHORIZED_POSITION_NOT_FOUND"));}
    public Authorization operation(String id){return jdbc.queryForList("SELECT metadata_json FROM futures_authorized_operations WHERE operation_id=?",String.class,id).stream().findFirst().map(s->read(s,Authorization.class)).orElseThrow(()->new Failure("AUTHORIZED_OPERATION_NOT_FOUND"));}
    public Optional<Map.Entry<FuturesReport,String>> fixedReport(String id){return jdbc.query("SELECT report_json,price_signature FROM futures_authorized_operations WHERE operation_id=? AND report_json IS NOT NULL",(r,n)->Map.entry(read(r.getString(1),FuturesReport.class),r.getString(2)),id).stream().findFirst();}
    @Transactional(timeout=5)
    public Operation automatic(long uid,String pid,String key,Action action,long version,Mark mark){
        if(!Set.of(Action.START_CALL,Action.CLEAR_CALL,Action.LIQUIDATE).contains(action))throw new Failure("USER_SIGNATURE_REQUIRED");
        var meta=position(pid,uid);var op=ledger.prepareAuthorized(uid,pid,key,action,version,mark.price(),mark.observedAt().getEpochSecond(),BigInteger.ZERO,UUID.randomUUID().toString());
        if(jdbc.queryForObject("SELECT count(*) FROM futures_authorized_operations WHERE operation_id=?",Long.class,op.id())==0)jdbc.update("INSERT INTO futures_authorized_operations VALUES(?,?,NULL,NULL)",op.id(),write(meta));
        bind(op,mark);return op;
    }
    private void bind(Operation op,Mark m){if(jdbc.queryForObject("SELECT count(*) FROM futures_market_evidence WHERE operation_id=?",Long.class,op.id())==0)jdbc.update("INSERT INTO futures_market_evidence VALUES(?,?,?,?,?)",op.id(),new BigDecimal(m.price()),Timestamp.from(m.observedAt()),m.provider(),m.generation());}
    private String write(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new Failure("AUTHORIZATION_ENCODING_FAILED");}}
    private <T>T read(String s,Class<T> type){try{return json.readValue(s,type);}catch(Exception e){throw new Failure("AUTHORIZATION_METADATA_INVALID");}}
}
