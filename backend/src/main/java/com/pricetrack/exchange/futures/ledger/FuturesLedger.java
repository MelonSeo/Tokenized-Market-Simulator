package com.pricetrack.exchange.futures.ledger;

import static com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.web3j.crypto.Hash;

/** Short DB-only prepare/settle transactions. No RPC, signing or external requests while holding locks. */
@Service @DependsOn("futuresSchema")
@ConditionalOnProperty(name="app.futures.enabled",havingValue="true")
public class FuturesLedger {
    public static final String POLICY_HASH=Hash.sha3String("isolated-v1-zero-fee-mmr500-grace60");
    public static final BigInteger INITIAL_CAPITAL=BigInteger.TEN.pow(25);
    private static final BigInteger ZERO=BigInteger.ZERO;
    private final JdbcTemplate jdbc;
    public FuturesLedger(DataSource source) { jdbc=new JdbcTemplate(source); jdbc.setQueryTimeout(3); }
    public static class Failure extends RuntimeException {
        public final String code;
        public Failure(String code) { super(code); this.code=code; }
    }

    /** Explicit fixture initialization only, caller must establish new-runtime ownership before invoking. */
    @Transactional(timeout=5)
    public void initializeEmptyFixture() {
        if(count("SELECT count(*) FROM futures_policy")!=0) { requirePolicy(); lockHouse(); return; }
        if(count("SELECT count(*) FROM futures_positions")!=0 || count("SELECT count(*) FROM futures_operations")!=0
            || count("SELECT count(*) FROM user_balances WHERE amount<>0 OR locked_amount<>0")!=0
            || count("SELECT count(*) FROM faucet_grants")!=0 || count("SELECT count(*) FROM trades")!=0
            || count("SELECT count(*) FROM orders")!=0 || count("SELECT count(*) FROM futures_house")!=0)
            throw new Failure("FRESH_ZERO_LEDGER_REQUIRED");
        jdbc.update("INSERT INTO futures_policy(id,policy_hash,initial_capital,created_at) VALUES(1,?,?,CURRENT_TIMESTAMP)",POLICY_HASH,decimal(INITIAL_CAPITAL));
        jdbc.update("INSERT INTO futures_house(id,amount,reserved) VALUES(1,?,0)",decimal(INITIAL_CAPITAL));
    }

    @Transactional(timeout=5)
    public Operation prepareOpen(long uid,String rawKey,Side side,BigInteger margin,int leverage,BigInteger price,long observedAt) {
        String key=fingerprint(rawKey), request=Hash.sha3String("OPEN|"+side+"|"+margin+"|"+leverage+"|"+price+"|"+observedAt);
        guard(uid); Operation replay=replay(uid,key,request); if(replay!=null) return replay;
        requirePolicy(); BigInteger q=FuturesMath.quantity(margin,leverage,price);
        String active=jdbc.queryForObject("SELECT active_position FROM futures_user_guards WHERE user_id=?",String.class,uid);
        if(active!=null) throw new Failure("ACTIVE_POSITION_EXISTS");
        String pid=UUID.randomUUID().toString(),op=UUID.randomUUID().toString();
        lockHouse(); Balance balance=balance(uid); reserve(balance,margin);
        String account=jdbc.queryForObject("SELECT account_ref FROM futures_user_guards WHERE user_id=?",String.class,uid);
        jdbc.update("INSERT INTO futures_positions(id,user_id,account_ref,side,leverage,quantity,entry_price,collateral,status,risk_status,pending_operation,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'OPEN_PENDING','HEALTHY',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            pid,uid,account,side.name(),leverage,decimal(q),decimal(price),decimal(margin),op);
        jdbc.update("UPDATE futures_user_guards SET active_position=? WHERE user_id=?",pid,uid);
        insertOperation(op,pid,uid,Action.OPEN,0,0,key,request,price,observedAt,ZERO,margin,margin,ZERO,ZERO,ZERO);
        journal(op,pid,uid,"RESERVE",ZERO,margin,ZERO,ZERO);
        return operation(op);
    }

    @Transactional(timeout=5)
    public Operation prepare(long uid,String pid,String rawKey,Action action,long expectedVersion,
                             BigInteger price,long observedAt,BigInteger addedMargin) {
        if(action==null || action==Action.OPEN) throw new Failure("INVALID_ACTION");
        String key=fingerprint(rawKey),request=Hash.sha3String(action+"|"+pid+"|"+expectedVersion+"|"+price+"|"+observedAt+"|"+addedMargin);
        guard(uid); Operation replay=replay(uid,key,request); if(replay!=null) return replay;
        Position p=positionForUpdate(pid,uid); requirePolicy();
        if(!"OPEN".equals(p.status()) || p.pendingOperation()!=null) throw new Failure("POSITION_BUSY_OR_CLOSED");
        if(p.version()!=expectedVersion) throw new Failure("STALE_POSITION_VERSION");
        if(observedAt<p.lastObservedAt()) throw new Failure("OLD_MARK");
        BigInteger after=p.collateral(), reservedMargin=ZERO;
        if(action==Action.TOP_UP) {
            if(addedMargin==null || addedMargin.signum()<=0) throw new Failure("INVALID_MARGIN");
            after=after.add(addedMargin); reservedMargin=addedMargin;
        } else if(addedMargin!=null && addedMargin.signum()!=0) throw new Failure("INVALID_MARGIN");
        var m=FuturesMath.mark(p.side(),p.quantity(),p.entryPrice(),after,price);
        if(action==Action.START_CALL && (!p.riskStatus().equals("HEALTHY") || !m.atRisk())) throw new Failure("RISK_CONDITION_NOT_MET");
        if(action==Action.CLEAR_CALL && (!p.riskStatus().equals("GRACE") || m.atRisk())) throw new Failure("RECOVERY_CONDITION_NOT_MET");
        if(action==Action.LIQUIDATE && (!p.riskStatus().equals("GRACE") || !m.atRisk() || observedAt<p.deadline())) throw new Failure("LIQUIDATION_NOT_ALLOWED");
        boolean terminal=action==Action.CLOSE || action==Action.LIQUIDATE;
        BigInteger realized=terminal?m.realized():ZERO,debt=terminal?m.badDebt():ZERO,houseReservation=realized.max(ZERO);
        House house=lockHouse(); Balance b=balance(uid); reserve(b,reservedMargin);
        requireWalletRange(b.amount().add(realized));
        if(house.amount().subtract(house.reserved()).compareTo(houseReservation)<0) throw new Failure("POOL_INSUFFICIENT");
        jdbc.update("UPDATE futures_house SET reserved=reserved+? WHERE id=1",decimal(houseReservation));
        String id=UUID.randomUUID().toString();
        insertOperation(id,pid,uid,action,p.version(),p.callEpoch(),key,request,price,observedAt,p.collateral(),after,reservedMargin,houseReservation,realized,debt);
        String state=action==Action.CLOSE?"CLOSING":action==Action.LIQUIDATE?"LIQUIDATING":"OPEN";
        jdbc.update("UPDATE futures_positions SET pending_operation=?,status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",id,state,pid);
        if(reservedMargin.signum()>0) journal(id,pid,uid,"RESERVE",ZERO,reservedMargin,ZERO,ZERO);
        return operation(id);
    }

    /** A receipt may settle only a durably signed transaction bound to this operation. */
    @Transactional(timeout=5)
    public void settle(ConfirmedEvent e) {
        Operation hint=operation(e.operationId()); guard(hint.userId());
        Position p=positionForUpdate(hint.positionId(),hint.userId());
        Operation op=operationForUpdate(hint.id());
        if(op.state().equals("CONFIRMED")) {
            var done=jdbc.queryForMap("SELECT tx_hash,block_number,block_hash FROM futures_transactions WHERE operation_id=?",op.id());
            if(!Objects.equals(done.get("tx_hash"),e.txHash()) || !Objects.equals(done.get("block_hash"),e.blockHash())
                || ((Number)done.get("block_number")).longValue()!=e.blockNumber()) throw new Failure("DUPLICATE_EVIDENCE_CONFLICT");
            return;
        }
        if(!Objects.equals(p.pendingOperation(),op.id()) || p.version()!=op.expectedVersion()) throw new Failure("SETTLEMENT_STATE_CONFLICT");
        var tx=jdbc.queryForMap("SELECT status,tx_hash FROM futures_transactions WHERE operation_id=? FOR UPDATE",op.id());
        if(!Set.of("SIGNED","SUBMITTED").contains(tx.get("status")) || !Objects.equals(tx.get("tx_hash"),e.txHash())) throw new Failure("TRANSACTION_NOT_VERIFIABLE");
        if(e.blockHash()==null || !e.blockHash().matches("0x[0-9a-fA-F]{64}") || e.blockNumber()<0
            || e.version()!=op.expectedVersion()+1 || !op.price().equals(e.price()) || !op.collateralAfter().equals(e.collateral())
            || !op.expectedRealized().equals(e.realized()) || !op.expectedBadDebt().equals(e.badDebt())) throw new Failure("EVENT_NOT_VERIFIABLE");
        boolean terminal=op.action()==Action.CLOSE || op.action()==Action.LIQUIDATE;
        long epoch=op.expectedEpoch()+(op.action()==Action.START_CALL?1:0);
        String risk=p.riskStatus(); long deadline=p.deadline();
        if(op.action()==Action.START_CALL) { risk="GRACE"; deadline=e.deadline(); if(deadline!=Math.addExact(e.blockTimestamp(),60)) throw new Failure("INVALID_DEADLINE"); }
        if(op.action()==Action.LIQUIDATE && (e.blockTimestamp()<p.deadline() || op.observedAt()<p.deadline() || op.observedAt()>e.blockTimestamp())) throw new Failure("INVALID_LIQUIDATION_TIME");
        if(op.action()==Action.CLEAR_CALL || (op.action()==Action.TOP_UP && !FuturesMath.mark(p.side(),p.quantity(),p.entryPrice(),op.collateralAfter(),op.price()).atRisk())) { risk="HEALTHY"; deadline=0; }
        if(e.callEpoch()!=epoch || e.deadline()!=deadline) throw new Failure("EVENT_NOT_VERIFIABLE");
        House h=lockHouse(); Balance b=balance(op.userId());
        BigInteger unlock=terminal?p.collateral():ZERO;
        if(b.locked().compareTo(unlock)<0 || h.reserved().compareTo(op.reservedHouse())<0) throw new Failure("RESERVATION_NOT_VERIFIABLE");
        BigInteger amount=b.amount().add(op.expectedRealized()),houseAfter=h.amount().subtract(op.expectedRealized());
        requireWalletRange(amount); if(houseAfter.signum()<0) throw new Failure("HOUSE_ACCOUNTING_CONFLICT");
        writeBalance(b,amount,b.locked().subtract(unlock));
        jdbc.update("UPDATE futures_house SET amount=?,reserved=reserved-? WHERE id=1",decimal(houseAfter),decimal(op.reservedHouse()));
        String state=terminal?(op.action()==Action.CLOSE?"CLOSED":"LIQUIDATED"):"OPEN";
        jdbc.update("UPDATE futures_positions SET status=?,risk_status=?,collateral=?,version=?,call_epoch=?,deadline=?,last_observed_at=?,pending_operation=NULL,realized=?,bad_debt=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
            state,risk,decimal(op.collateralAfter()),e.version(),epoch,deadline,op.observedAt(),terminal?decimal(e.realized()):null,terminal?decimal(e.badDebt()):null,p.id());
        if(terminal) jdbc.update("UPDATE futures_user_guards SET active_position=NULL WHERE user_id=? AND active_position=?",op.userId(),p.id());
        jdbc.update("UPDATE futures_operations SET state='CONFIRMED',active_position_id=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",op.id());
        jdbc.update("UPDATE futures_transactions SET status='CONFIRMED',block_number=?,block_hash=?,confirmed_at=CURRENT_TIMESTAMP WHERE operation_id=?",e.blockNumber(),e.blockHash(),op.id());
        journal(op.id(),p.id(),op.userId(),"CONFIRM",op.expectedRealized(),unlock.negate(),op.expectedRealized().negate(),op.expectedBadDebt());
    }

    @Transactional(timeout=5)
    public void rejectUnsigned(String id,String reason) {
        Operation hint=operation(id); guard(hint.userId()); Position p=positionForUpdate(hint.positionId(),hint.userId());
        Operation op=operationForUpdate(id);
        if(op.state().equals("REJECTED")) return;
        if(!op.state().equals("PREPARED") || count("SELECT count(*) FROM futures_transactions WHERE operation_id=?",id)!=0
            || !Objects.equals(p.pendingOperation(),id)) throw new Failure("SIGNED_OR_NONPENDING_OPERATION");
        House h=lockHouse(); Balance b=balance(op.userId());
        if(b.locked().compareTo(op.reservedMargin())<0 || h.reserved().compareTo(op.reservedHouse())<0) throw new Failure("RESERVATION_NOT_VERIFIABLE");
        writeBalance(b,b.amount(),b.locked().subtract(op.reservedMargin()));
        jdbc.update("UPDATE futures_house SET reserved=reserved-? WHERE id=1",decimal(op.reservedHouse()));
        jdbc.update("UPDATE futures_operations SET state='REJECTED',active_position_id=NULL,reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",safeReason(reason),id);
        jdbc.update("UPDATE futures_positions SET status=?,pending_operation=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",op.action()==Action.OPEN?"OPEN_FAILED":"OPEN",p.id());
        if(op.action()==Action.OPEN) jdbc.update("UPDATE futures_user_guards SET active_position=NULL WHERE user_id=?",op.userId());
        journal(id,p.id(),op.userId(),"RELEASE",ZERO,op.reservedMargin().negate(),ZERO,ZERO);
    }

    /** Invoked by the facade only AFTER the failed prepare transaction has rolled back. No asset changes. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void recordBlocked(long uid,String pid,long expectedVersion,String reason) {
        if(!Set.of("POOL_INSUFFICIENT","MARKET_UNAVAILABLE","PRICE_CHANGED").contains(reason)) throw new Failure("INVALID_BLOCK_REASON");
        guard(uid); Position p=positionForUpdate(pid,uid);
        if(p.version()==expectedVersion && p.pendingOperation()==null && p.status().equals("OPEN"))
            jdbc.update("UPDATE futures_positions SET block_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",reason,pid);
    }

    public Position position(String id,long uid) {
        return jdbc.query("SELECT * FROM futures_positions WHERE id=? AND user_id=?",(r,n)->readPosition(r),id,uid)
            .stream().findFirst().orElseThrow(()->new Failure("POSITION_NOT_FOUND"));
    }
    public List<Position> positions(long uid) { return jdbc.query("SELECT * FROM futures_positions WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 50",(r,n)->readPosition(r),uid); }
    public Operation operation(String id) { return jdbc.query("SELECT * FROM futures_operations WHERE id=?",(r,n)->readOperation(r),id).stream().findFirst().orElseThrow(()->new Failure("OPERATION_NOT_FOUND")); }
    private Operation operationForUpdate(String id) { return jdbc.queryForObject("SELECT * FROM futures_operations WHERE id=? FOR UPDATE",(r,n)->readOperation(r),id); }
    private Position positionForUpdate(String id,long uid) { return jdbc.query("SELECT * FROM futures_positions WHERE id=? AND user_id=? FOR UPDATE",(r,n)->readPosition(r),id,uid).stream().findFirst().orElseThrow(()->new Failure("POSITION_NOT_FOUND")); }
    private void guard(long uid) {
        String account=Hash.sha3String(UUID.randomUUID().toString());
        boolean pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
        if(pg) jdbc.update("INSERT INTO futures_user_guards(user_id,account_ref) VALUES(?,?) ON CONFLICT(user_id) DO NOTHING",uid,account);
        else jdbc.update("INSERT INTO futures_user_guards(user_id,account_ref) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM futures_user_guards WHERE user_id=?)",uid,account,uid);
        jdbc.queryForObject("SELECT user_id FROM futures_user_guards WHERE user_id=? FOR UPDATE",Long.class,uid);
    }
    private Operation replay(long uid,String key,String request) {
        var rows=jdbc.query("SELECT * FROM futures_operations WHERE user_id=? AND key_hash=?",(r,n)->readOperation(r),uid,key);
        if(rows.isEmpty()) return null;
        String stored=jdbc.queryForObject("SELECT request_hash FROM futures_operations WHERE id=?",String.class,rows.get(0).id());
        if(!Objects.equals(stored,request)) throw new Failure("IDEMPOTENCY_CONFLICT");
        return rows.get(0);
    }
    private void requirePolicy() {
        var rows=jdbc.queryForList("SELECT policy_hash,initial_capital FROM futures_policy WHERE id=1");
        if(rows.size()!=1 || !POLICY_HASH.equals(rows.get(0).get("policy_hash")) || !INITIAL_CAPITAL.equals(((BigDecimal)rows.get(0).get("initial_capital")).toBigIntegerExact())) throw new Failure("FUTURES_POLICY_NOT_INITIALIZED");
    }
    private record House(BigInteger amount,BigInteger reserved) {}
    private House lockHouse() {
        House h=jdbc.queryForObject("SELECT amount,reserved FROM futures_house WHERE id=1 FOR UPDATE",(r,n)->new House(integer(r,"amount"),integer(r,"reserved")));
        BigInteger delta=jdbc.queryForObject("SELECT COALESCE(SUM(house_delta),0) FROM futures_journal",BigDecimal.class).toBigIntegerExact();
        BigInteger reserved=jdbc.queryForObject("SELECT COALESCE(SUM(reserved_house),0) FROM futures_operations WHERE active_position_id IS NOT NULL",BigDecimal.class).toBigIntegerExact();
        if(!h.amount().equals(INITIAL_CAPITAL.add(delta)) || !h.reserved().equals(reserved)) throw new Failure("HOUSE_JOURNAL_CONFLICT");
        return h;
    }
    private record Balance(long userId,BigInteger amount,BigInteger locked) {}
    private Balance balance(long uid) { return jdbc.queryForObject("SELECT user_id,amount,locked_amount FROM user_balances WHERE user_id=? AND symbol='mKRW' FOR UPDATE",(r,n)->new Balance(r.getLong(1),TokenUnits.toWei(r.getBigDecimal(2)),TokenUnits.toWei(r.getBigDecimal(3))),uid); }
    private void reserve(Balance b,BigInteger value) { if(value.signum()<0 || b.locked().signum()<0 || b.amount().subtract(b.locked()).compareTo(value)<0) throw new Failure("INSUFFICIENT_AVAILABLE_MARGIN"); writeBalance(b,b.amount(),b.locked().add(value)); }
    private void writeBalance(Balance b,BigInteger amount,BigInteger locked) { requireWalletRange(amount); if(locked.signum()<0 || locked.compareTo(amount)>0) throw new Failure("INVALID_WALLET_LOCK"); jdbc.update("UPDATE user_balances SET amount=?,locked_amount=? WHERE user_id=? AND symbol='mKRW'",TokenUnits.fromWei(amount),TokenUnits.fromWei(locked),b.userId()); }
    private void requireWalletRange(BigInteger value) { if(value.signum()<0 || value.compareTo(FuturesMath.AMOUNT_LIMIT)>=0) throw new Failure("WALLET_RANGE_EXCEEDED"); }
    private void insertOperation(String id,String pid,long uid,Action a,long v,long epoch,String key,String request,BigInteger price,long observed,BigInteger before,BigInteger after,BigInteger margin,BigInteger house,BigInteger realized,BigInteger debt) {
        jdbc.update("INSERT INTO futures_operations(id,position_id,active_position_id,user_id,action,expected_version,expected_epoch,key_hash,request_hash,price,observed_at,collateral_before,collateral_after,reserved_margin,reserved_house,expected_realized,expected_bad_debt,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,pid,pid,uid,a.name(),v,epoch,key,request,decimal(price),observed,decimal(before),decimal(after),decimal(margin),decimal(house),decimal(realized),decimal(debt));
    }
    private void journal(String op,String pid,long uid,String type,BigInteger wallet,BigInteger locked,BigInteger house,BigInteger debt) { jdbc.update("INSERT INTO futures_journal(id,operation_id,position_id,user_id,entry_type,wallet_delta,locked_delta,house_delta,bad_debt,created_at) VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),op,pid,uid,type,decimal(wallet),decimal(locked),decimal(house),decimal(debt)); }
    private long count(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    private static BigDecimal decimal(BigInteger n) { return new BigDecimal(n); }
    private static BigInteger integer(ResultSet r,String key)throws SQLException { BigDecimal n=r.getBigDecimal(key); return n==null?null:n.toBigIntegerExact(); }
    private Position readPosition(ResultSet r)throws SQLException { return new Position(r.getString("id"),r.getLong("user_id"),r.getString("account_ref"),Side.valueOf(r.getString("side")),r.getInt("leverage"),integer(r,"quantity"),integer(r,"entry_price"),integer(r,"collateral"),r.getString("status"),r.getString("risk_status"),r.getLong("version"),r.getLong("call_epoch"),r.getLong("deadline"),r.getLong("last_observed_at"),r.getString("pending_operation"),r.getString("block_reason"),integer(r,"realized"),integer(r,"bad_debt"),r.getTimestamp("created_at").toInstant()); }
    private Operation readOperation(ResultSet r)throws SQLException { return new Operation(r.getString("id"),r.getString("position_id"),r.getLong("user_id"),Action.valueOf(r.getString("action")),r.getLong("expected_version"),r.getLong("expected_epoch"),integer(r,"price"),r.getLong("observed_at"),integer(r,"collateral_before"),integer(r,"collateral_after"),integer(r,"reserved_margin"),integer(r,"reserved_house"),integer(r,"expected_realized"),integer(r,"expected_bad_debt"),r.getString("state")); }
    public static String fingerprint(String key) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{16,128}")) throw new Failure("INVALID_IDEMPOTENCY_KEY");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("futures:v1:"+key).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String safeReason(String code) { return code!=null && code.matches("[A-Z][A-Z0-9_]{0,79}")?code:"PREPARATION_REJECTED"; }
}
