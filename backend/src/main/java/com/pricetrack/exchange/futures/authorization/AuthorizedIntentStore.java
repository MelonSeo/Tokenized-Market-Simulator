package com.pricetrack.exchange.futures.authorization;

import com.pricetrack.exchange.futures.runtime.*;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import java.math.BigInteger;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reuses market evidence/worker; only selection and server-owned risk prepare differ by version. */
public class AuthorizedIntentStore extends FuturesIntentStore {
    private final JdbcTemplate jdbc;private final AuthorizedFuturesStore authorized;
    public AuthorizedIntentStore(DataSource ds,FuturesLedger ledger,AuthorizedFuturesStore store){super(ds,ledger);jdbc=new JdbcTemplate(ds);authorized=store;}
    @Override public List<String> prepared(){return jdbc.queryForList("SELECT o.id FROM futures_operations o JOIN futures_authorized_operations a ON a.operation_id=o.id WHERE state='PREPARED' AND NOT EXISTS(SELECT 1 FROM futures_authorized_v2_operations v WHERE v.operation_id=o.id) ORDER BY created_at,o.id LIMIT 50",String.class);}
    @Override public Operation prepare(long uid,String pid,String key,Action action,long version,BigInteger added,FuturesMarketGate.Mark mark){return authorized.automatic(uid,pid,key,action,version,mark);}
}
