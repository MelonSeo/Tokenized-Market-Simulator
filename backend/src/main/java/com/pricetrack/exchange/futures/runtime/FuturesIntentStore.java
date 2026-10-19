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
        if(action==Action.TOP_UP) {var old=marginReplay(uid,pid,key,version,added);if(old.isPresent())return old.get();}
        Operation op=ledger.prepare(uid,pid,key,action,version,mark.price(),mark.observedAt().getEpochSecond(),added);
        bind(op,mark);return op;
    }
    private void bind(Operation op,FuturesMarketGate.Mark mark) {
        var existing=jdbc.queryForList("SELECT operation_id FROM futures_market_evidence WHERE operation_id=?",String.class,op.id());
        if(existing.isEmpty())jdbc.update("INSERT INTO futures_market_evidence VALUES(?,?,?,?,?)",op.id(),new BigDecimal(mark.price()),Timestamp.from(mark.observedAt()),mark.provider(),mark.generation());
        else if(!mark.equals(evidence(op.id())))throw new Failure("IDEMPOTENCY_CONFLICT");
    }
    public FuturesMarketGate.Mark evidence(String op) {
        return jdbc.query("SELECT * FROM futures_market_evidence WHERE operation_id=?",(r,n)->new FuturesMarketGate.Mark(r.getBigDecimal("price").toBigIntegerExact(),r.getTimestamp("observed_at").toInstant(),r.getString("provider"),(Long)r.getObject("generation")),op)
            .stream().findFirst().orElseThrow(()->new Failure("MARKET_EVIDENCE_MISSING"));
    }
    public List<String> prepared() { return jdbc.queryForList("SELECT id FROM futures_operations WHERE state='PREPARED' ORDER BY created_at,id LIMIT 50",String.class); }
    public Optional<Operation> marginReplay(long uid,String pid,String key,long version,BigInteger added) {
        var ids=jdbc.queryForList("SELECT id FROM futures_operations WHERE user_id=? AND key_hash=?",String.class,uid,FuturesLedger.fingerprint(key));
        if(ids.isEmpty())return Optional.empty();
        var op=ledger.operation(ids.get(0));
        if(!op.positionId().equals(pid) || op.action()!=Action.TOP_UP || op.expectedVersion()!=version || !op.reservedMargin().equals(added))throw new Failure("IDEMPOTENCY_CONFLICT");
        return Optional.of(op);
    }
}
