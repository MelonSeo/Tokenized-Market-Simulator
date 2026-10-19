package com.pricetrack.exchange.futures.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import java.math.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Internal bounded snapshot. Contains signing evidence: never serialize this record into API/history/AI output. */
public record FuturesAuditSnapshot(String policy,BigInteger initial,BigInteger house,BigInteger reserved,
        List<Position> positions,List<Operation> operations,List<Tx> transactions,List<Journal> journal,List<Guard> guards) {
    public record Tx(FuturesTransactionStore.Stored stored,Long block,String blockHash) {}
    public record Journal(String id,String operationId,String positionId,long userId,String type,
            BigInteger wallet,BigInteger locked,BigInteger house,BigInteger debt) {}
    public record Guard(long userId,String accountRef,String activePosition) {}
    public static class Limit extends RuntimeException {}
    public static class Unavailable extends RuntimeException {}
    private static <T> List<T> bound(List<T> rows) {if(rows.size()>10000)throw new Limit();return List.copyOf(rows);}
    private static BigInteger n(ResultSet r,String name)throws SQLException {var v=r.getBigDecimal(name);return v==null?null:v.toBigIntegerExact();}
    /** Caller supplies one existing REPEATABLE_READ read-only transaction; this method never invokes RPC or takes row locks. */
    public static FuturesAuditSnapshot read(JdbcTemplate jdbc,ObjectMapper json) {
        var policy=jdbc.queryForList("SELECT policy_hash,initial_capital FROM futures_policy WHERE id=1");
        var house=jdbc.queryForList("SELECT amount,reserved FROM futures_house WHERE id=1");
        var positions=bound(jdbc.query("SELECT * FROM futures_positions ORDER BY id LIMIT 10001",(r,i)->new Position(
            r.getString("id"),r.getLong("user_id"),r.getString("account_ref"),Side.valueOf(r.getString("side")),r.getInt("leverage"),n(r,"quantity"),n(r,"entry_price"),n(r,"collateral"),
            r.getString("status"),r.getString("risk_status"),r.getLong("version"),r.getLong("call_epoch"),r.getLong("deadline"),r.getLong("last_observed_at"),r.getString("pending_operation"),r.getString("block_reason"),n(r,"realized"),n(r,"bad_debt"),r.getTimestamp("created_at").toInstant())));
        var operations=bound(jdbc.query("SELECT * FROM futures_operations ORDER BY id LIMIT 10001",(r,i)->new Operation(
            r.getString("id"),r.getString("position_id"),r.getLong("user_id"),Action.valueOf(r.getString("action")),r.getLong("expected_version"),r.getLong("expected_epoch"),n(r,"price"),r.getLong("observed_at"),
            n(r,"collateral_before"),n(r,"collateral_after"),n(r,"reserved_margin"),n(r,"reserved_house"),n(r,"expected_realized"),n(r,"expected_bad_debt"),r.getString("state"))));
        var transactions=bound(jdbc.query("SELECT t.*,o.report_json,o.signature FROM futures_transactions t LEFT JOIN futures_operations o ON o.id=t.operation_id ORDER BY t.id LIMIT 10001",(r,i)->{
            try {return new Tx(new FuturesTransactionStore.Stored(r.getString("operation_id"),r.getString("raw_transaction"),r.getString("tx_hash"),r.getLong("nonce"),r.getString("status"),n(r,"chain_id"),
                r.getString("contract_address"),r.getString("sender"),json.readValue(r.getString("report_json"),FuturesReport.class),r.getString("signature")),r.getObject("block_number",Long.class),r.getString("block_hash"));}
            catch(Exception unavailable){throw new Unavailable();}
        }));
        var journal=bound(jdbc.query("SELECT * FROM futures_journal ORDER BY id LIMIT 10001",(r,i)->new Journal(r.getString("id"),r.getString("operation_id"),r.getString("position_id"),r.getLong("user_id"),
            r.getString("entry_type"),n(r,"wallet_delta"),n(r,"locked_delta"),n(r,"house_delta"),n(r,"bad_debt"))));
        var guards=bound(jdbc.query("SELECT * FROM futures_user_guards ORDER BY user_id LIMIT 10001",(r,i)->new Guard(r.getLong("user_id"),r.getString("account_ref"),r.getString("active_position"))));
        return new FuturesAuditSnapshot(policy.size()==1?(String)policy.get(0).get("policy_hash"):"",
            policy.size()==1?((BigDecimal)policy.get(0).get("initial_capital")).toBigIntegerExact():BigInteger.ZERO,
            house.size()==1?((BigDecimal)house.get(0).get("amount")).toBigIntegerExact():BigInteger.ZERO,
            house.size()==1?((BigDecimal)house.get(0).get("reserved")).toBigIntegerExact():BigInteger.ZERO,positions,operations,transactions,journal,guards);
    }
    public boolean unsettled() {return operations.stream().anyMatch(o->!Set.of("CONFIRMED","REVERTED","REJECTED").contains(o.state()))
        || transactions.stream().anyMatch(t->!Set.of("CONFIRMED","REVERTED").contains(t.stored().status()))
        || positions.stream().anyMatch(p->p.pendingOperation()!=null || p.status().equals("REVIEW_REQUIRED"));}
    public boolean empty() {return positions.isEmpty()&&operations.isEmpty()&&transactions.isEmpty()&&journal.isEmpty();}
    @Override public String toString(){return "FuturesAuditSnapshot[internal signing evidence redacted]";}
}
