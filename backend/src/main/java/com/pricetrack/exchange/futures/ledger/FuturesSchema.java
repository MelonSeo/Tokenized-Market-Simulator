package com.pricetrack.exchange.futures.ledger;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/** Disabled by default; does not seed capital or adopt legacy users/balances. */
@Component
@DependsOn("entityManagerFactory")
@ConditionalOnProperty(name="app.futures.enabled",havingValue="true")
public class FuturesSchema {
    private final DataSource source;
    public FuturesSchema(DataSource source) { this.source=source; }
    @PostConstruct public void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("futures-schema.sql")).execute(source);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(source);
        boolean pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
            c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
        if(pg) {
            jdbc.execute("CREATE OR REPLACE FUNCTION reject_futures_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'append-only futures evidence'; END $$");
            for(String table:java.util.List.of("futures_policy","futures_journal")) {
                if(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname=? AND tgrelid=?::regclass",Integer.class,table+"_immutable",table)==0)
                    jdbc.execute("CREATE TRIGGER "+table+"_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON "+table+" FOR EACH STATEMENT EXECUTE FUNCTION reject_futures_evidence_mutation()");
            }
        }
    }
}
