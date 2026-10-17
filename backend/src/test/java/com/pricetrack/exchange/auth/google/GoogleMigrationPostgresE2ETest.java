package com.pricetrack.exchange.auth.google;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import com.pricetrack.exchange.user.UserRepository;

/** Legacy schema rehearsal only, independent of the NEW empty-ledger trading fixture. */
@EnabledIfEnvironmentVariable(named="GOOGLE_MIGRATION_E2E_TESTS",matches="true")
@SpringBootTest(properties={"app.google.enabled=true","app.google.client-id=offline-google-client",
        "app.google.allowed-origins=http://localhost:5173"})
@Import(GoogleTestIdentity.class) @DirtiesContext
class GoogleMigrationPostgresE2ETest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry registry)throws Exception {
        var config=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("GOOGLE_MIGRATION_E2E_RUN"));
        if(config==null)throw new IllegalStateException("New owned migration fixture required");
        try(var connection=DriverManager.getConnection(config.getProperty("spring.datasource.url"),
                config.getProperty("spring.datasource.username"),config.getProperty("spring.datasource.password"));var statement=connection.createStatement()) {
            try(var rows=statement.executeQuery("SELECT to_regclass('public.users')")){
                rows.next();if(rows.getString(1)!=null)throw new IllegalStateException("Unused migration fixture required");
            }
            statement.execute("CREATE TABLE users (id BIGSERIAL PRIMARY KEY, login_id VARCHAR(50) UNIQUE, email VARCHAR(255) UNIQUE NOT NULL, password_hash VARCHAR(255) NOT NULL, nickname VARCHAR(100) NOT NULL, wallet_address VARCHAR(255), created_at TIMESTAMP NOT NULL)");
            statement.execute("INSERT INTO users(login_id,email,password_hash,nickname,created_at) VALUES ('legacy_fixture','legacy@example.test','old-hash-unchanged','Legacy fixture',CURRENT_TIMESTAMP)");
        }
        config.forEach((k,v)->registry.add(k.toString(),()->v));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired GoogleAccountService google;
    @Autowired javax.sql.DataSource ds;
    @Test void additiveSqlPreservesLegacyUserAndDoesNotActivateHistoricalFaucet() {
        var before=jdbc.queryForMap("SELECT * FROM users WHERE login_id='legacy_fixture'");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql")).execute(ds);
        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE login_id='legacy_fixture'")).isEqualTo(before);
        assertThat(users.findByLoginId("legacy_fixture").orElseThrow().getPasswordHash()).isEqualTo("old-hash-unchanged");
        assertThat(jdbc.queryForObject("SELECT initialized FROM faucet_budget_usage WHERE id=1",Boolean.class)).isFalse();
        String binding=GoogleAccountService.random();var challenge=google.issue(binding,null);
        google.login(challenge.challengeId(),binding,GoogleTestIdentity.token("migration-new-google",challenge.nonce()));
        var created=users.findByGoogleSub("migration-new-google").orElseThrow();
        assertThat(created.getEmail()).isNull();assertThat(created.getLoginId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_balances WHERE user_id=? AND amount=0 AND locked_amount=0",Integer.class,created.getId())).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE login_id='legacy_fixture'")).isEqualTo(before);
    }
}
