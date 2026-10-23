package com.pricetrack.exchange.futures.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.Authorization;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import java.math.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Internal bounded snapshot. Contains signing evidence: never serialize this record into API/history/AI output. */
public record FuturesAuditSnapshot(String policy,BigInteger initial,BigInteger house,BigInteger reserved,
        List<Position> positions,List<Operation> operations,List<Tx> transactions,List<Journal> journal,List<Guard> guards,
        Map<String,FuturesAuthorizedAuditEvidence> authorizations,Map<String,Authorization> positionAuthorizations) {
    public FuturesAuditSnapshot {authorizations=Map.copyOf(authorizations);positionAuthorizations=Map.copyOf(positionAuthorizations);}
    public FuturesAuditSnapshot(String policy,BigInteger initial,BigInteger house,BigInteger reserved,List<Position> positions,
            List<Operation> operations,List<Tx> transactions,List<Journal> journal,List<Guard> guards){
        this(policy,initial,house,reserved,positions,operations,transactions,journal,guards,Map.of(),Map.of());
    }
    public record Tx(FuturesTransactionStore.Stored stored,Long block,String blockHash) {}
    public record Journal(String id,String operationId,String positionId,long userId,String type,
            BigInteger wallet,BigInteger locked,BigInteger house,BigInteger debt) {}
    public record Guard(long userId,String accountRef,String activePosition) {}
    public static class Limit extends RuntimeException {}
    public static class Unavailable extends RuntimeException {}
    public static class AuthorizedCoverageUnavailable extends Unavailable {}
    private static <T> List<T> bound(List<T> rows) {if(rows.size()>10000)throw new Limit();return List.copyOf(rows);}
    private static BigInteger n(ResultSet r,String name)throws SQLException {var v=r.getBigDecimal(name);return v==null?null:v.toBigIntegerExact();}
    /** Caller supplies one existing REPEATABLE_READ read-only transaction; this method never invokes RPC or takes row locks. */
    public static FuturesAuditSnapshot read(JdbcTemplate jdbc,ObjectMapper json) {
        // Legacy audit cannot claim complete coverage of the two-contract/user-signature ABI.
        if(jdbc.queryForObject("SELECT count(*) FROM futures_authorized_positions",Long.class)>0)throw new AuthorizedCoverageUnavailable();
        return readConsistent(jdbc,json,false);
    }
    /** New explicit audit path only. Legacy callers keep their incomplete-coverage guard. */
    public static FuturesAuditSnapshot readAuthorized(JdbcTemplate jdbc,ObjectMapper json){return readConsistent(jdbc,json,true);}
    private static <T>T decode(ObjectMapper json,String value,Class<T> type){
        if(value==null)return null;try{return json.readValue(value,type);}catch(Exception malformed){throw new Unavailable();}
    }
    private static FuturesAuditSnapshot readConsistent(JdbcTemplate jdbc,ObjectMapper json,boolean includeAuthorized){
        var policy=jdbc.queryForList("SELECT policy_hash,initial_capital FROM futures_policy WHERE id=1");
        var house=jdbc.queryForList("SELECT amount,reserved FROM futures_house WHERE id=1");
        var positions=bound(jdbc.query("SELECT * FROM futures_positions ORDER BY id LIMIT 10001",(r,i)->new Position(
            r.getString("id"),r.getLong("user_id"),r.getString("account_ref"),Side.valueOf(r.getString("side")),r.getInt("leverage"),n(r,"quantity"),n(r,"entry_price"),n(r,"collateral"),
            r.getString("status"),r.getString("risk_status"),r.getLong("version"),r.getLong("call_epoch"),r.getLong("deadline"),r.getLong("last_observed_at"),r.getString("pending_operation"),r.getString("block_reason"),n(r,"realized"),n(r,"bad_debt"),r.getTimestamp("created_at").toInstant())));
        var operations=bound(jdbc.query("SELECT * FROM futures_operations ORDER BY id LIMIT 10001",(r,i)->new Operation(
            r.getString("id"),r.getString("position_id"),r.getLong("user_id"),Action.valueOf(r.getString("action")),r.getLong("expected_version"),r.getLong("expected_epoch"),n(r,"price"),r.getLong("observed_at"),
            n(r,"collateral_before"),n(r,"collateral_after"),n(r,"reserved_margin"),n(r,"reserved_house"),n(r,"expected_realized"),n(r,"expected_bad_debt"),r.getString("state"))));
        var pa=new HashMap<String,Authorization>();var oa=new HashMap<String,Authorization>();
        var originalReports=new HashMap<String,FuturesReport>();var originalSignatures=new HashMap<String,String>();
        var wallets=new HashMap<Long,String>();var pc=new HashMap<String,String>();var oc=new HashMap<String,String>();
        var heads=new HashMap<String,Integer>();var revisions=new HashMap<String,List<FuturesAuthorizedAuditEvidence.Revision>>();
        if(includeAuthorized){
            bound(jdbc.query("SELECT position_id,user_id,metadata_json FROM futures_authorized_positions ORDER BY position_id LIMIT 10001",(r,i)->{
                String positionId=r.getString("position_id");var p=positions.stream().filter(v->v.id().equals(positionId)).findFirst().orElseThrow(Unavailable::new);
                if(p.userId()!=r.getLong("user_id"))throw new Unavailable();
                pa.put(p.id(),decode(json,r.getString("metadata_json"),Authorization.class));return p.id();
            }));
            bound(jdbc.query("SELECT operation_id,metadata_json,report_json,price_signature FROM futures_authorized_operations ORDER BY operation_id LIMIT 10001",(r,i)->{
                String id=r.getString("operation_id");oa.put(id,decode(json,r.getString("metadata_json"),Authorization.class));
                originalReports.put(id,decode(json,r.getString("report_json"),FuturesReport.class));originalSignatures.put(id,r.getString("price_signature"));return id;
            }));
            bound(jdbc.query("SELECT user_id,wallet FROM futures_wallet_links ORDER BY user_id LIMIT 10001",(r,i)->{wallets.put(r.getLong(1),r.getString(2));return r.getLong(1);}));
            bound(jdbc.query("SELECT position_id,controller FROM futures_authorized_v2_positions ORDER BY position_id LIMIT 10001",(r,i)->{pc.put(r.getString(1),r.getString(2));return r.getString(1);}));
            bound(jdbc.query("SELECT operation_id,controller FROM futures_authorized_v2_operations ORDER BY operation_id LIMIT 10001",(r,i)->{oc.put(r.getString(1),r.getString(2));return r.getString(1);}));
            bound(jdbc.query("SELECT operation_id,active_revision FROM futures_v2_execution_heads ORDER BY operation_id LIMIT 10001",(r,i)->{heads.put(r.getString(1),r.getInt(2));return r.getString(1);}));
            bound(jdbc.query("SELECT * FROM futures_v2_execution_revisions ORDER BY operation_id,revision LIMIT 10001",(r,i)->{
                String id=r.getString("operation_id");var revision=new FuturesAuthorizedAuditEvidence.Revision(r.getInt("revision"),decode(json,r.getString("report_json"),FuturesReport.class),
                    r.getString("price_signature"),n(r,"price"),r.getTimestamp("observed_at").toInstant().getEpochSecond(),r.getString("provider"),r.getObject("generation",Long.class));
                revisions.computeIfAbsent(id,k->new ArrayList<>()).add(revision);return id;
            }));
            var ids=operations.stream().map(Operation::id).collect(java.util.stream.Collectors.toSet());
            if(!ids.containsAll(oa.keySet())||!ids.containsAll(oc.keySet())||!ids.containsAll(heads.keySet())||!ids.containsAll(revisions.keySet()))throw new Unavailable();
        }
        var evidence=new HashMap<String,FuturesAuthorizedAuditEvidence>();
        if(includeAuthorized)for(var o:operations)if(pa.containsKey(o.positionId())||oa.containsKey(o.id())||oc.containsKey(o.id())||heads.containsKey(o.id())||revisions.containsKey(o.id()))
            evidence.put(o.id(),new FuturesAuthorizedAuditEvidence(o.id(),o.positionId(),o.userId(),pa.get(o.positionId()),oa.get(o.id()),wallets.get(o.userId()),pc.get(o.positionId()),oc.get(o.id()),
                originalReports.get(o.id()),originalSignatures.get(o.id()),heads.get(o.id()),revisions.getOrDefault(o.id(),List.of())));
        var transactions=bound(jdbc.query("SELECT t.*,o.report_json,o.signature FROM futures_transactions t LEFT JOIN futures_operations o ON o.id=t.operation_id ORDER BY t.id LIMIT 10001",(r,i)->{
            try {return new Tx(new FuturesTransactionStore.Stored(r.getString("operation_id"),r.getString("raw_transaction"),r.getString("tx_hash"),r.getLong("nonce"),r.getString("status"),n(r,"chain_id"),
                r.getString("contract_address"),r.getString("sender"),json.readValue(r.getString("report_json"),FuturesReport.class),r.getString("signature"),oa.get(r.getString("operation_id"))),r.getObject("block_number",Long.class),r.getString("block_hash"));}
            catch(Exception unavailable){throw new Unavailable();}
        }));
        var journal=bound(jdbc.query("SELECT * FROM futures_journal ORDER BY id LIMIT 10001",(r,i)->new Journal(r.getString("id"),r.getString("operation_id"),r.getString("position_id"),r.getLong("user_id"),
            r.getString("entry_type"),n(r,"wallet_delta"),n(r,"locked_delta"),n(r,"house_delta"),n(r,"bad_debt"))));
        var guards=bound(jdbc.query("SELECT * FROM futures_user_guards ORDER BY user_id LIMIT 10001",(r,i)->new Guard(r.getLong("user_id"),r.getString("account_ref"),r.getString("active_position"))));
        return new FuturesAuditSnapshot(policy.size()==1?(String)policy.get(0).get("policy_hash"):"",
            policy.size()==1?((BigDecimal)policy.get(0).get("initial_capital")).toBigIntegerExact():BigInteger.ZERO,
            house.size()==1?((BigDecimal)house.get(0).get("amount")).toBigIntegerExact():BigInteger.ZERO,
            house.size()==1?((BigDecimal)house.get(0).get("reserved")).toBigIntegerExact():BigInteger.ZERO,positions,operations,transactions,journal,guards,evidence,pa);
    }
    public boolean unsettled() {return operations.stream().anyMatch(o->!Set.of("CONFIRMED","REVERTED","REJECTED").contains(o.state()))
        || transactions.stream().anyMatch(t->!Set.of("CONFIRMED","REVERTED").contains(t.stored().status()))
        || positions.stream().anyMatch(p->p.pendingOperation()!=null || p.status().equals("REVIEW_REQUIRED"));}
    public boolean empty() {return positions.isEmpty()&&operations.isEmpty()&&transactions.isEmpty()&&journal.isEmpty();}
    @Override public String toString(){return "FuturesAuditSnapshot[internal signing evidence redacted]";}
}
