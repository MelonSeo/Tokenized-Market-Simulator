package com.pricetrack.exchange.futures.authorization;
import com.pricetrack.exchange.futures.chain.FuturesChainIdentity;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.runtime.*;
import java.math.BigInteger;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
public class AuthorizedV2IntentStore extends FuturesIntentStore {
    private final JdbcTemplate jdbc;private final AuthorizedV2Store store;private final FuturesChainIdentity chain;
    public AuthorizedV2IntentStore(DataSource ds,FuturesLedger l,AuthorizedV2Store s,FuturesChainIdentity c){super(ds,l);jdbc=new JdbcTemplate(ds);store=s;chain=c;}
    @Override public List<String> prepared(){return jdbc.queryForList("SELECT o.id FROM futures_operations o JOIN futures_authorized_v2_operations v ON v.operation_id=o.id WHERE o.state='PREPARED' AND v.controller=? ORDER BY o.created_at,o.id LIMIT 50",String.class,chain.engine());}
    @Override public FuturesMarketGate.Mark evidence(String id){
        var rows=jdbc.query("SELECT r.* FROM futures_v2_execution_revisions r JOIN futures_v2_execution_heads h ON h.operation_id=r.operation_id AND h.active_revision=r.revision WHERE r.operation_id=?",(r,n)->new FuturesMarketGate.Mark(r.getBigDecimal("price").toBigIntegerExact(),r.getTimestamp("observed_at").toInstant(),r.getString("provider"),(Long)r.getObject("generation")),id);
        return rows.isEmpty()?super.evidence(id):rows.get(0);
    }
    @Override public Operation prepare(long uid,String pid,String key,Action a,long version,BigInteger added,FuturesMarketGate.Mark mark){return store.automatic(uid,pid,key,a,version,mark,chain);}
}
