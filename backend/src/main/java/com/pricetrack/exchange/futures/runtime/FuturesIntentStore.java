package com.pricetrack.exchange.futures.runtime;

import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Short DB-only atomic preview consumption + existing ledger prepare. Never performs RPC under balance locks. */
public class FuturesIntentStore {
    public record Preview(String id,long userId,Action action,String positionId,long version,Side side,int leverage,
        BigInteger margin,FuturesMarketGate.Mark mark,Instant expiresAt,String operationId,String keyHash) {}
    private final JdbcTemplate jdbc;
    private final FuturesLedger ledger;
    public FuturesIntentStore(DataSource ds,FuturesLedger ledger) { jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(3);this.ledger=ledger; }

    @Transactional(timeout=5)
    public Preview preview(long uid,Action action,String pid,long version,Side side,int leverage,BigInteger margin,FuturesMarketGate.Mark mark,Instant now) {
        if(action==Action.OPEN) { if(side==null)throw new Failure("INVALID_SIDE");FuturesMath.quantity(margin,leverage,mark.price());pid=null;version=0; }
        else if(action==Action.CLOSE) {
            var p=ledger.position(pid,uid);
            if(!p.status().equals("OPEN") || p.pendingOperation()!=null || p.version()!=version)throw new Failure("POSITION_BUSY_OR_CLOSED");
            side=p.side();leverage=p.leverage();margin=BigInteger.ZERO;
        } else throw new Failure("INVALID_PREVIEW_ACTION");
        String id=UUID.randomUUID().toString();
        // Preview expires with its original report observation; it is never extended by a replay.
        Instant expires=mark.observedAt().plusSeconds(30);
        jdbc.update("INSERT INTO futures_previews(id,user_id,action,position_id,expected_version,side,leverage,margin,price,observed_at,provider,generation,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,uid,action.name(),pid,version,side.name(),leverage,new BigDecimal(margin),new BigDecimal(mark.price()),Timestamp.from(mark.observedAt()),mark.provider(),mark.generation(),Timestamp.from(expires),Timestamp.from(now));
        return find(uid,id,false);
    }
    public Preview find(long uid,String id,boolean lock) {
        return jdbc.query("SELECT * FROM futures_previews WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),(r,n)->new Preview(r.getString("id"),uid,Action.valueOf(r.getString("action")),r.getString("position_id"),r.getLong("expected_version"),Side.valueOf(r.getString("side")),r.getInt("leverage"),r.getBigDecimal("margin").toBigIntegerExact(),
            new FuturesMarketGate.Mark(r.getBigDecimal("price").toBigIntegerExact(),r.getTimestamp("observed_at").toInstant(),r.getString("provider"),(Long)r.getObject("generation")),r.getTimestamp("expires_at").toInstant(),r.getString("operation_id"),r.getString("key_hash")),id,uid)
            .stream().findFirst().orElseThrow(()->new Failure("PREVIEW_NOT_FOUND"));
    }
    @Transactional(timeout=5)
    public Operation consume(long uid,String id,String key,Action action,String pid,long version,Instant now) {
        String fingerprint=FuturesLedger.fingerprint(key);Preview v=find(uid,id,true);
        if(v.action()!=action || !Objects.equals(v.positionId(),pid) || v.version()!=version)throw new Failure("PREVIEW_ACTION_CONFLICT");
        if(v.operationId()!=null) {
            if(!fingerprint.equals(v.keyHash()))throw new Failure("PREVIEW_ALREADY_CONSUMED");
            return ledger.operation(v.operationId());
        }
        ledger.lockUserIntent(uid);checkRejection(uid,key,previewRequest(id,action,pid,version));
        if(!now.isBefore(v.expiresAt()))throw new Failure("PREVIEW_EXPIRED");
        Operation op=action==Action.OPEN?ledger.prepareOpen(uid,key,v.side(),v.margin(),v.leverage(),v.mark().price(),v.mark().observedAt().getEpochSecond()):
            ledger.prepare(uid,pid,key,action,version,v.mark().price(),v.mark().observedAt().getEpochSecond(),BigInteger.ZERO);
        bind(op,v.mark());
        jdbc.update("UPDATE futures_previews SET operation_id=?,key_hash=? WHERE id=?",op.id(),fingerprint,id);
        return op;
    }
    @Transactional(timeout=5)
    public Operation prepare(long uid,String pid,String key,Action action,long version,BigInteger added,FuturesMarketGate.Mark mark) {
        ledger.lockUserIntent(uid);
        if(action==Action.TOP_UP)checkRejection(uid,key,marginRequest(pid,version,added));
        if(action==Action.TOP_UP) {var old=marginReplay(uid,pid,key,version,added);if(old.isPresent())return old.get();}
        Operation op=ledger.prepare(uid,pid,key,action,version,mark.price(),mark.observedAt().getEpochSecond(),added);
        bind(op,mark);return op;
    }
    protected void bind(Operation op,FuturesMarketGate.Mark mark) {
        var existing=jdbc.queryForList("SELECT operation_id FROM futures_market_evidence WHERE operation_id=?",String.class,op.id());
        if(existing.isEmpty())jdbc.update("INSERT INTO futures_market_evidence VALUES(?,?,?,?,?)",op.id(),new BigDecimal(mark.price()),Timestamp.from(mark.observedAt()),mark.provider(),mark.generation());
        else if(!mark.equals(evidence(op.id())))throw new Failure("IDEMPOTENCY_CONFLICT");
    }
    public FuturesMarketGate.Mark evidence(String op) {
        return jdbc.query("SELECT * FROM futures_market_evidence WHERE operation_id=?",(r,n)->new FuturesMarketGate.Mark(r.getBigDecimal("price").toBigIntegerExact(),r.getTimestamp("observed_at").toInstant(),r.getString("provider"),(Long)r.getObject("generation")),op)
            .stream().findFirst().orElseThrow(()->new Failure("MARKET_EVIDENCE_MISSING"));
    }
    public List<String> prepared() { return jdbc.queryForList("SELECT id FROM futures_operations o WHERE state='PREPARED' AND NOT EXISTS(SELECT 1 FROM futures_authorized_positions a WHERE a.position_id=o.position_id) ORDER BY created_at,id LIMIT 50",String.class); }
    public Optional<Operation> marginReplay(long uid,String pid,String key,long version,BigInteger added) {
        var ids=jdbc.queryForList("SELECT id FROM futures_operations WHERE user_id=? AND key_hash=?",String.class,uid,FuturesLedger.fingerprint(key));
        if(ids.isEmpty())return Optional.empty();
        var op=ledger.operation(ids.get(0));
        if(!op.positionId().equals(pid) || op.action()!=Action.TOP_UP || op.expectedVersion()!=version || !op.reservedMargin().equals(added))throw new Failure("IDEMPOTENCY_CONFLICT");
        return Optional.of(op);
    }
    private String previewRequest(String id,Action action,String pid,long version){return org.web3j.crypto.Hash.sha3String("PREVIEW|"+id+"|"+action+"|"+pid+"|"+version);}
    private String marginRequest(String pid,long version,BigInteger added){return org.web3j.crypto.Hash.sha3String("MARGIN|"+pid+"|"+version+"|"+added);}
    private void checkRejection(long uid,String key,String request){
        var rows=jdbc.queryForList("SELECT request_hash FROM futures_request_rejections WHERE user_id=? AND key_hash=?",String.class,uid,FuturesLedger.fingerprint(key));
        if(!rows.isEmpty())throw new Failure(rows.get(0).equals(request)?"FUTURES_REQUEST_REJECTED":"IDEMPOTENCY_CONFLICT");
    }
    private void reject(long uid,String key,String request){
        var rows=jdbc.queryForList("SELECT request_hash FROM futures_request_rejections WHERE user_id=? AND key_hash=?",String.class,uid,FuturesLedger.fingerprint(key));
        if(!rows.isEmpty()){if(!rows.get(0).equals(request))throw new Failure("IDEMPOTENCY_CONFLICT");return;}
        jdbc.update("INSERT INTO futures_request_rejections VALUES(?,?,?,CURRENT_TIMESTAMP)",uid,FuturesLedger.fingerprint(key),request);
    }
    /** Same preview->user lock order as consume: return the earlier accepted request OR seal no-execution. */
    @Transactional(timeout=5)
    public Optional<Operation> rejectPreview(long uid,String id,String key,Action action,String pid,long version){
        var v=find(uid,id,true);String fingerprint=FuturesLedger.fingerprint(key);
        if(v.action()!=action || !Objects.equals(v.positionId(),pid) || v.version()!=version)throw new Failure("PREVIEW_ACTION_CONFLICT");
        ledger.lockUserIntent(uid);
        if(v.operationId()!=null){if(!fingerprint.equals(v.keyHash()))throw new Failure("PREVIEW_ALREADY_CONSUMED");return Optional.of(ledger.operation(v.operationId()));}
        if(jdbc.queryForObject("SELECT count(*) FROM futures_operations WHERE user_id=? AND key_hash=?",Long.class,uid,fingerprint)!=0)throw new Failure("IDEMPOTENCY_CONFLICT");
        reject(uid,key,previewRequest(id,action,pid,version));return Optional.empty();
    }
    @Transactional(timeout=5)
    public Optional<Operation> rejectMargin(long uid,String pid,String key,long version,BigInteger added){
        ledger.lockUserIntent(uid);var previous=marginReplay(uid,pid,key,version,added);if(previous.isPresent())return previous;
        reject(uid,key,marginRequest(pid,version,added));return Optional.empty();
    }
}
