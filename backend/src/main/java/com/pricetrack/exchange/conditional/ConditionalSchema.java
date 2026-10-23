package com.pricetrack.exchange.conditional;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import com.pricetrack.exchange.common.schema.SchemaStartupMode;
import com.pricetrack.exchange.common.schema.PreinstalledSchemaVerifier;
import org.springframework.jdbc.core.JdbcTemplate;

/** Additive DDL only: no existing orders, balances, grants or chain data are migrated. */
@Component @DependsOn("entityManagerFactory")
public class ConditionalSchema {
    private final DataSource dataSource;
    private final SchemaStartupMode startupMode;
    public ConditionalSchema(DataSource dataSource){this(dataSource,SchemaStartupMode.INSTALL);}
    @Autowired public ConditionalSchema(DataSource dataSource,@Value("${app.schema.startup-mode:install}")String mode,Environment environment){
        this(dataSource,SchemaStartupMode.resolve(mode,environment));
    }
    private ConditionalSchema(DataSource dataSource,SchemaStartupMode mode){this.dataSource=dataSource;this.startupMode=mode;}
    @PostConstruct void initialize(){
        if(startupMode==SchemaStartupMode.VERIFY_EXISTING) {
            try(var connection=dataSource.getConnection()) {
                if(!connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"))
                    throw new IllegalStateException("PREINSTALLED_SCHEMA_POSTGRESQL_REQUIRED");
            } catch(java.sql.SQLException unavailable) { throw new IllegalStateException("PREINSTALLED_SCHEMA_UNAVAILABLE",unavailable); }
            var jdbc=new JdbcTemplate(dataSource);jdbc.setQueryTimeout(3);PreinstalledSchemaVerifier.conditional(jdbc);return;
        }
        new ResourceDatabasePopulator(new ClassPathResource("conditional-schema.sql")).execute(dataSource);
    }
}
