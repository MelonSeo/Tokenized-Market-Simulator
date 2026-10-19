package com.pricetrack.exchange.futures.read;

import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.runtime.FuturesMarketGate;
import java.math.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit public allowlist. No raw transaction, signature, accountRef, keys or report JSON can be serialized. */
public final class FuturesReadService {
    public record PositionView(String id,String side,int leverage,String quantity,String entryPrice,String collateral,
        String status,String riskStatus,long version,long callEpoch,long deadline,String pendingOperation,String blockReason,
        String markPrice,String unrealized,String equity,String maintenance,String realized,String badDebt,
        boolean priceAvailable,Instant observedAt,Instant createdAt) {}
    public record OperationView(String id,String positionId,String action,String state,String reason,String txHash,Long nonce,
        Long blockNumber,String blockHash,String expectedRealized,String expectedBadDebt,boolean settlementConfirmed) {}
    private final FuturesLedger ledger;private final FuturesMarketGate market;private final JdbcTemplate jdbc;
    public FuturesReadService(DataSource ds,FuturesLedger ledger,FuturesMarketGate market) { this.jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(3);this.ledger=ledger;this.market=market; }
    public static String amount(BigInteger n) { return n==null?null:new BigDecimal(n,18).toPlainString(); }
    public static String price(BigInteger n) { return n==null?null:new BigDecimal(n,8).toPlainString(); }
    public PositionView position(long uid,String id) { return view(ledger.position(id,uid)); }
    public List<PositionView> positions(long uid) { return ledger.positions(uid).stream().map(this::view).toList(); }
    private PositionView view(Position p) {
        FuturesMarketGate.Mark mark=null;FuturesMath.Mark m=null;
        try {mark=market.capture();m=FuturesMath.mark(p.side(),p.quantity(),p.entryPrice(),p.collateral(),mark.price());}
        catch(RuntimeException unavailable){mark=null;}
        if(m!=null && Set.of("CLOSED","LIQUIDATED","OPEN_FAILED").contains(p.status()))m=null;
        return new PositionView(p.id(),p.side().name(),p.leverage(),amount(p.quantity()),price(p.entryPrice()),amount(p.collateral()),p.status(),p.riskStatus(),p.version(),p.callEpoch(),p.deadline(),p.pendingOperation(),p.blockReason(),
            mark==null?null:price(mark.price()),m==null?null:amount(m.unrealized()),m==null?null:amount(m.equity()),m==null?null:amount(m.maintenance()),amount(p.realized()),amount(p.badDebt()),mark!=null,mark==null?null:mark.observedAt(),p.createdAt());
    }
    public OperationView operation(long uid,String id) {
        Operation op=ledger.operation(id);if(op.userId()!=uid)throw new FuturesLedger.Failure("OPERATION_NOT_FOUND");
        var tx=jdbc.queryForList("SELECT tx_hash,nonce,status,block_number,block_hash FROM futures_transactions WHERE operation_id=?",id);
        String reason=jdbc.queryForObject("SELECT reason FROM futures_operations WHERE id=?",String.class,id);
        var t=tx.isEmpty()?Map.<String,Object>of():tx.get(0);
        return new OperationView(id,op.positionId(),op.action().name(),op.state(),reason,(String)t.get("tx_hash"),longValue(t.get("nonce")),longValue(t.get("block_number")),(String)t.get("block_hash"),amount(op.expectedRealized()),amount(op.expectedBadDebt()),"CONFIRMED".equals(op.state())&&"CONFIRMED".equals(t.get("status")));
    }
    private static Long longValue(Object v) { return v==null?null:((Number)v).longValue(); }
    public Map<String,Object> house() {
        var row=jdbc.queryForMap("SELECT amount,reserved FROM futures_house WHERE id=1");
        return Map.of("amount",amount(((BigDecimal)row.get("amount")).toBigIntegerExact()),"reserved",amount(((BigDecimal)row.get("reserved")).toBigIntegerExact()),"initialCapital",amount(FuturesLedger.INITIAL_CAPITAL),"policyHash",FuturesLedger.POLICY_HASH,"onchainEscrow",false,"payoutGuaranteed",false);
    }
    public List<PositionView> adminPositions(long uid) { return positions(uid); }
}
