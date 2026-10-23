package com.pricetrack.exchange.futures.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import java.math.BigInteger;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.Authorization;

/** Commits immutable signed intent before broadcasting. No RPC call runs in these transactions. */
@Service @DependsOn("futuresSchema")
@ConditionalOnProperty(name="app.futures.enabled",havingValue="true")
public class FuturesTransactionStore {
    private final JdbcTemplate jdbc;
    private final FuturesLedger ledger;
    private final ObjectMapper json;
    public FuturesTransactionStore(DataSource ds,FuturesLedger ledger,ObjectMapper json) {
        jdbc=new JdbcTemplate(ds); jdbc.setQueryTimeout(3); this.ledger=ledger; this.json=json;
    }
    public record Stored(String operationId,String raw,String hash,long nonce,String status,BigInteger chainId,
                         String engine,String sender,FuturesReport report,String signature,Authorization authorization) {
        public Stored(String id,String raw,String hash,long nonce,String status,BigInteger chain,String engine,String sender,FuturesReport report,String signature){this(id,raw,hash,nonce,status,chain,engine,sender,report,signature,null);}
        @Override public String toString() { return "FuturesTransaction[operationId="+operationId+",status="+status+"]"; }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public Stored saveSigned(String id,FuturesChainIdentity chain,FuturesReport report,String signature,
                             String raw,String hash,long nonce) {
        lockSender(chain);
        String state=jdbc.queryForObject("SELECT state FROM futures_operations WHERE id=? FOR UPDATE",String.class,id);
        var op=ledger.operation(id);
        var position=ledger.position(op.positionId(),op.userId());
        var existing=find(id);
        if(existing.isPresent()) {
            Stored s=existing.get();
            if(!s.raw().equals(raw) || !s.hash().equals(hash)) fail("SIGNED_INTENT_CONFLICT");
            return s;
        }
        if(!"PREPARED".equals(state) || !id.equals(position.pendingOperation())) fail("OPERATION_NOT_PREPARED");
        assertActiveExecution(id,report,signature);
        if(!FuturesReport.from(op,position,chain.reportExecutor()).equals(report)
            || !FuturesReportCodec.signedBy(report,chain.chainId(),chain.priceContract(),signature,chain.priceSigner())) fail("REPORT_NOT_AUTHORIZED");
        if(hasUnresolved(chain.executor())) fail("FUTURES_EXECUTOR_BUSY");
        validateRaw(chain,report,signature,raw,hash,nonce,authorization(id));
        try {
            jdbc.update("UPDATE futures_operations SET report_json=?,signature=?,state='SIGNED',updated_at=CURRENT_TIMESTAMP WHERE id=?",
                json.writeValueAsString(report),signature,id);
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("REPORT_ENCODING_FAILED"); }
        jdbc.update("INSERT INTO futures_transactions(id,operation_id,sender,nonce,chain_id,contract_address,raw_transaction,tx_hash,status,created_at) VALUES(?,?,?,?,?,?,?,?,'SIGNED',CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(),id,chain.executor(),nonce,chain.chainId(),chain.engine(),raw,hash);
        return find(id).orElseThrow();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void markSubmitted(String id,String hash) {
        jdbc.queryForObject("SELECT state FROM futures_operations WHERE id=? FOR UPDATE",String.class,id);
        // Never regress a concurrently confirmed/reviewed transaction back to SUBMITTED.
        int n=jdbc.update("UPDATE futures_transactions SET status='SUBMITTED',submitted_at=COALESCE(submitted_at,CURRENT_TIMESTAMP) WHERE operation_id=? AND tx_hash=? AND status IN ('SIGNED','SUBMITTED')",id,hash);
        if(n>0) jdbc.update("UPDATE futures_operations SET state='SUBMITTED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND state IN ('SIGNED','SUBMITTED')",id);
    }

    public Optional<Stored> find(String id) {
        return jdbc.query("SELECT t.*,o.report_json,o.signature FROM futures_transactions t JOIN futures_operations o ON o.id=t.operation_id WHERE operation_id=?",(r,n)-> {
            try { return new Stored(id,r.getString("raw_transaction"),r.getString("tx_hash"),r.getLong("nonce"),r.getString("status"),
                r.getBigDecimal("chain_id").toBigIntegerExact(),r.getString("contract_address"),r.getString("sender"),
                json.readValue(r.getString("report_json"),FuturesReport.class),r.getString("signature"),authorization(id)); }
            catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("STORED_REPORT_INVALID"); }
        },id).stream().findFirst();
    }
    public boolean hasUnresolved(String sender) {
        // REVIEW_REQUIRED also blocks nonce admission; ambiguity is not permission to create another transaction.
        return jdbc.queryForObject("SELECT count(*) FROM futures_transactions WHERE sender=? AND status IN ('SIGNED','SUBMITTED','REVIEW_REQUIRED')",Long.class,sender)>0;
    }
    public List<String> pending(int limit) {
        if(limit<1 || limit>50) throw new IllegalArgumentException("INVALID_LIMIT");
        return jdbc.queryForList("SELECT operation_id FROM futures_transactions t WHERE status IN ('SIGNED','SUBMITTED') AND NOT EXISTS(SELECT 1 FROM futures_authorized_operations a WHERE a.operation_id=t.operation_id) ORDER BY created_at,operation_id LIMIT ?",String.class,limit);
    }
    public List<String> pending(int limit,String engine){if(limit<1||limit>50)throw new IllegalArgumentException("INVALID_LIMIT");return jdbc.queryForList("SELECT operation_id FROM futures_transactions WHERE contract_address=? AND status IN ('SIGNED','SUBMITTED') ORDER BY created_at,operation_id LIMIT ?",String.class,engine,limit);}
    public Authorization authorization(String id){return jdbc.queryForList("SELECT metadata_json FROM futures_authorized_operations WHERE operation_id=?",String.class,id).stream().findFirst().map(s->{try{return json.readValue(s,Authorization.class);}catch(Exception invalid){throw new FuturesLedger.Failure("AUTHORIZATION_METADATA_INVALID");}}).orElse(null);}
    public int executionRevision(String id){var rows=jdbc.queryForList("SELECT active_revision FROM futures_v2_execution_heads WHERE operation_id=?",Integer.class,id);return rows.isEmpty()?-1:rows.get(0);}
    public Optional<Map.Entry<FuturesReport,String>> fixedAuthorizedReport(String id){
        String sql=executionRevision(id)<0?"SELECT report_json,price_signature FROM futures_authorized_operations WHERE operation_id=? AND report_json IS NOT NULL":
            "SELECT r.report_json,r.price_signature FROM futures_v2_execution_revisions r JOIN futures_v2_execution_heads h ON h.operation_id=r.operation_id AND h.active_revision=r.revision WHERE r.operation_id=?";
        return jdbc.query(sql,(r,n)->{try{return Map.entry(json.readValue(r.getString(1),FuturesReport.class),r.getString(2));}catch(Exception e){throw new FuturesLedger.Failure("AUTHORIZATION_METADATA_INVALID");}},id).stream().findFirst();
    }
    public void assertActiveExecution(String id,FuturesReport report,String signature){
        var a=authorization(id);if(a==null||!a.chain().v2()||!UserIntentCodec.userAction(report.action()))return;
        var fixed=fixedAuthorizedReport(id).orElseThrow(()->new FuturesLedger.Failure("AUTHORIZED_REPORT_CONFLICT"));
        if(!fixed.getKey().equals(report)||!fixed.getValue().equals(signature))fail("REVISION_CONFLICT");
    }
    public String encoding(String id,FuturesChainIdentity c,FuturesReport r,String signature){
        var a=authorization(id);if(c.authorized()){if(a==null||!c.equals(a.chain()))fail("AUTHORIZATION_IDENTITY_CONFLICT");return UserIntentCodec.encode(r,signature,a);}
        if(a!=null)fail("FUTURES_CONTRACT_VERSION_CONFLICT");return FuturesReportCodec.encode(r,signature);
    }
    private void lockSender(FuturesChainIdentity c) {
        boolean pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)x->x.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
        if(pg) jdbc.update("INSERT INTO futures_sender_guards VALUES(?,?,?) ON CONFLICT(sender) DO NOTHING",c.executor(),c.chainId(),c.engine());
        else jdbc.update("INSERT INTO futures_sender_guards SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM futures_sender_guards WHERE sender=?)",c.executor(),c.chainId(),c.engine(),c.executor());
        var row=jdbc.queryForMap("SELECT chain_id,contract_address FROM futures_sender_guards WHERE sender=? FOR UPDATE",c.executor());
        if(!c.chainId().equals(((java.math.BigDecimal)row.get("chain_id")).toBigIntegerExact()) || !c.engine().equals(row.get("contract_address"))) fail("SENDER_DEPLOYMENT_CONFLICT");
    }
    public static void validateRaw(FuturesChainIdentity c,FuturesReport report,String signature,String raw,String hash,long nonce) {
        validateRaw(c,report,signature,raw,hash,nonce,null);
    }
    public static void validateRaw(FuturesChainIdentity c,FuturesReport report,String signature,String raw,String hash,long nonce,Authorization auth) {
        try {
            if(c.authorized()?(auth==null||!c.equals(auth.chain())):auth!=null)fail("AUTHORIZATION_IDENTITY_CONFLICT");
            String data=auth==null?FuturesReportCodec.encode(report,signature):UserIntentCodec.encode(report,signature,auth);
            if(raw==null || !raw.matches("0x[0-9a-fA-F]+") || hash==null
                    || !hash.equalsIgnoreCase(Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(raw))))) fail("RAW_HASH_CONFLICT");
            var decoded=TransactionDecoder.decode(raw);
            if(!(decoded instanceof SignedRawTransaction signed) || !signed.getFrom().equalsIgnoreCase(c.executor())
                || !Objects.equals(signed.getChainId(),c.chainId().longValueExact()) || !signed.getTo().equalsIgnoreCase(c.engine())
                || !signed.getNonce().equals(BigInteger.valueOf(nonce)) || signed.getValue().signum()!=0
                || !Numeric.cleanHexPrefix(signed.getData()).equalsIgnoreCase(Numeric.cleanHexPrefix(data))) fail("RAW_REPORT_CONFLICT");
        } catch(Exception invalid) { throw new FuturesLedger.Failure("SIGNED_RAW_NOT_VERIFIABLE"); }
    }
    private static void fail(String reason) { throw new FuturesLedger.Failure(reason); }
}
