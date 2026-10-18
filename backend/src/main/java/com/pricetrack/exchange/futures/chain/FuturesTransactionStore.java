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
                         String engine,String sender,FuturesReport report,String signature) {
        @Override public String toString() { return "FuturesTransaction[operationId="+operationId+",status="+status+"]"; }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public Stored saveSigned(String id,FuturesChainIdentity chain,FuturesReport report,String signature,
                             String raw,String hash,long nonce) {
        lockSender(chain);
        var op=ledger.operation(id);
        var position=ledger.position(op.positionId(),op.userId());
        String state=jdbc.queryForObject("SELECT state FROM futures_operations WHERE id=? FOR UPDATE",String.class,id);
        var existing=find(id);
        if(existing.isPresent()) {
            Stored s=existing.get();
            if(!s.raw().equals(raw) || !s.hash().equals(hash)) fail("SIGNED_INTENT_CONFLICT");
            return s;
        }
        if(!"PREPARED".equals(state) || !id.equals(position.pendingOperation())) fail("OPERATION_NOT_PREPARED");
        if(!FuturesReport.from(op,position,chain.executor()).equals(report)
            || !FuturesReportCodec.signedBy(report,chain.chainId(),chain.engine(),signature,chain.priceSigner())) fail("REPORT_NOT_AUTHORIZED");
        if(hasUnresolved(chain.executor())) fail("FUTURES_EXECUTOR_BUSY");
        validateRaw(chain,report,signature,raw,hash,nonce);
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
                json.readValue(r.getString("report_json"),FuturesReport.class),r.getString("signature")); }
            catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("STORED_REPORT_INVALID"); }
        },id).stream().findFirst();
    }
    public boolean hasUnresolved(String sender) {
        // REVIEW_REQUIRED also blocks nonce admission; ambiguity is not permission to create another transaction.
        return jdbc.queryForObject("SELECT count(*) FROM futures_transactions WHERE sender=? AND status IN ('SIGNED','SUBMITTED','REVIEW_REQUIRED')",Long.class,sender)>0;
    }
    public List<String> pending(int limit) {
        if(limit<1 || limit>50) throw new IllegalArgumentException("INVALID_LIMIT");
        return jdbc.queryForList("SELECT operation_id FROM futures_transactions WHERE status IN ('SIGNED','SUBMITTED') ORDER BY created_at,operation_id LIMIT ?",String.class,limit);
    }
    private void lockSender(FuturesChainIdentity c) {
        boolean pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)x->x.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
        if(pg) jdbc.update("INSERT INTO futures_sender_guards VALUES(?,?,?) ON CONFLICT(sender) DO NOTHING",c.executor(),c.chainId(),c.engine());
        else jdbc.update("INSERT INTO futures_sender_guards SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM futures_sender_guards WHERE sender=?)",c.executor(),c.chainId(),c.engine(),c.executor());
        var row=jdbc.queryForMap("SELECT chain_id,contract_address FROM futures_sender_guards WHERE sender=? FOR UPDATE",c.executor());
        if(!c.chainId().equals(((java.math.BigDecimal)row.get("chain_id")).toBigIntegerExact()) || !c.engine().equals(row.get("contract_address"))) fail("SENDER_DEPLOYMENT_CONFLICT");
    }
    static void validateRaw(FuturesChainIdentity c,FuturesReport report,String signature,String raw,String hash,long nonce) {
        try {
            if(raw==null || !raw.matches("0x[0-9a-fA-F]+") || hash==null
                    || !hash.equalsIgnoreCase(Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(raw))))) fail("RAW_HASH_CONFLICT");
            var decoded=TransactionDecoder.decode(raw);
            if(!(decoded instanceof SignedRawTransaction signed) || !signed.getFrom().equalsIgnoreCase(c.executor())
                || !Objects.equals(signed.getChainId(),c.chainId().longValueExact()) || !signed.getTo().equalsIgnoreCase(c.engine())
                || !signed.getNonce().equals(BigInteger.valueOf(nonce)) || signed.getValue().signum()!=0
                || !Numeric.cleanHexPrefix(signed.getData()).equalsIgnoreCase(Numeric.cleanHexPrefix(FuturesReportCodec.encode(report,signature)))) fail("RAW_REPORT_CONFLICT");
        } catch(Exception invalid) { throw new FuturesLedger.Failure("SIGNED_RAW_NOT_VERIFIABLE"); }
    }
    private static void fail(String reason) { throw new FuturesLedger.Failure(reason); }
}
