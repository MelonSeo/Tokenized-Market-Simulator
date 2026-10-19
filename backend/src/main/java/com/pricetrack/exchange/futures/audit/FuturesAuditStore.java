package com.pricetrack.exchange.futures.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.reserve.*;
import static com.pricetrack.exchange.futures.audit.FuturesAuditModels.*;
import java.sql.Timestamp;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.web3j.crypto.Hash;

/** Consistent read-only asset/operation cut. Only baseline and audit-history INSERTs are allowed here. */
@Service @DependsOn("futuresSchema")
@ConditionalOnProperty(name="app.futures.enabled",havingValue="true")
public class FuturesAuditStore {
    public record Cut(FuturesAuditSnapshot futures,ReserveModels.DbCut spot) {
        @Override public String toString(){return "FuturesAuditCut[internal evidence redacted]";}
    }
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public FuturesAuditStore(DataSource source,ObjectMapper json){jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(3);this.json=json;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW,timeout=5)
    public Cut cut(){
        var s=FuturesAuditSnapshot.read(jdbc,json);var impact=FuturesAuditAccounting.impact(s,json);
        return new Cut(s,ReserveStore.readSpot(jdbc,impact));
    }
    public String fingerprint(Cut c){return Hash.sha3String(encode(List.of(c.futures(),c.spot().balances(),c.spot().orders(),c.spot().trades(),c.spot().transactions(),c.spot().grants())));}
    public Optional<Baseline> baseline(){return jdbc.query("SELECT payload FROM futures_audit_baselines WHERE id=1",(r,i)->decode(r.getString(1),Baseline.class)).stream().findFirst();}
    @Transactional(timeout=5) public Baseline create(Baseline b){jdbc.update("INSERT INTO futures_audit_baselines(id,payload,actor_id,created_at) VALUES(1,?,?,?)",encode(b),b.actorId(),Timestamp.from(b.dbAt()));return b;}
    @Transactional(timeout=5) public Result save(Result r){jdbc.update("INSERT INTO futures_audit_runs(id,payload,actor_id,created_at) VALUES(?,?,?,?)",r.id(),encode(r),r.actorId(),Timestamp.from(r.createdAt()));return r;}
    public List<Result> history(){return jdbc.query("SELECT payload FROM futures_audit_runs ORDER BY created_at DESC,id DESC LIMIT 20",(r,i)->decode(r.getString(1),Result.class));}
    public Optional<Result> result(String id){return jdbc.query("SELECT payload FROM futures_audit_runs WHERE id=?",(r,i)->decode(r.getString(1),Result.class),id).stream().findFirst();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw new FuturesAuditSnapshot.Unavailable();}}
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception invalid){throw new FuturesAuditSnapshot.Unavailable();}}
}
