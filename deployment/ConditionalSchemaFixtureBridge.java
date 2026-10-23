package com.pricetrack.exchange.conditional;
import javax.sql.DataSource;
import java.util.Map;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
/** Fixture-only package peer, not a Spring application or replacement schema initializer. */
public final class ConditionalSchemaFixtureBridge {
    public static void install(DataSource source) { new ConditionalSchema(source).initialize(); }
    public static void verify(DataSource source) {
        var env=new StandardEnvironment();env.setActiveProfiles("local-read-gate");
        env.getPropertySources().addFirst(new MapPropertySource("fixed-fixture",Map.of(
                "spring.sql.init.mode","never","spring.jpa.hibernate.ddl-auto","none")));
        new ConditionalSchema(source,"verify-existing",env).initialize();
    }
}
