package com.pricetrack.exchange.futures.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.audit.FuturesAuditDeployment.*;
import java.sql.Timestamp;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** INSERT/read only. Schema initialization and historical baseline eligibility belong to the caller. */
public final class FuturesAuditDeploymentStore {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final Registry registry;private final DataSourceTransactionManager transactions;
    public FuturesAuditDeploymentStore(DataSource source,ObjectMapper json,Registry registry){
        jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(3);this.json=Objects.requireNonNull(json);this.registry=Objects.requireNonNull(registry);
        transactions=new DataSourceTransactionManager(source);
    }
    public Optional<Envelope> find(FuturesAuditDeployment target){
        registry.require(target);
        return jdbc.query("SELECT payload FROM futures_audit_deployments WHERE deployment_key=?",(r,n)->decode(r.getString(1)),target.key())
                .stream().peek(e->{if(!target.equals(e.deployment()))throw unavailable();}).findFirst();
    }
    /** No automatic seed/adoption: caller must independently validate canonical history and the DB cut. */
    public Envelope register(Envelope envelope){
        var target=envelope.deployment();registry.require(target);
        FuturesAuditCancellation.check();
        var transaction=new TransactionTemplate(transactions);transaction.setTimeout(FuturesAuditCancellation.transactionSeconds());
        return transaction.execute(status->{
            FuturesAuditCancellation.check();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void beforeCommit(boolean readOnly){FuturesAuditCancellation.check();}
            });
        // DO NOTHING makes concurrent identical registration safe without aborting a PostgreSQL transaction.
        try {
            FuturesAuditCancellation.check();
            jdbc.update("INSERT INTO futures_audit_deployments(deployment_key,chain_id,controller_address,rules_address,route,payload,actor_id,created_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    target.key(),target.chain().chainId().longValueExact(),target.chain().engine(),target.chain().priceContract(),target.route().name(),
                    encode(envelope),envelope.baseline().actorId(),Timestamp.from(envelope.baseline().dbAt()));
        } catch(DuplicateKeyException conflict){throw new IllegalStateException("FUTURES_AUDIT_DEPLOYMENT_CONFLICT");}
        var stored=find(target).orElseThrow(()->new IllegalStateException("FUTURES_AUDIT_DEPLOYMENT_CONFLICT"));
        if(!stored.equals(envelope))throw new IllegalStateException("FUTURES_AUDIT_BASELINE_ALREADY_FIXED");
        // An observed cancellation/deadline rolls the INSERT back before returning to commit.
        // A cancellation after this fence or an uncertain commit still requires a baseline lookup.
        FuturesAuditCancellation.check();
        return stored;
        });
    }
    private String encode(Envelope value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw unavailable();}}
    private Envelope decode(String value){try{return json.readValue(value,Envelope.class);}catch(Exception invalid){throw unavailable();}}
    private static IllegalStateException unavailable(){return new IllegalStateException("FUTURES_AUDIT_DEPLOYMENT_UNAVAILABLE");}
}
