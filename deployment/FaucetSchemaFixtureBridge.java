package com.pricetrack.exchange.wallet.faucet;
import javax.sql.DataSource;
import java.util.Map;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
/** Fixture-only package peer; invokes the actual unchanged installer, never copied policy SQL. */
public final class FaucetSchemaFixtureBridge {
    public static void install(DataSource source) throws Exception { new FaucetStore(source,"kst-v1").initialize(); }
    public static void verify(DataSource source,String version) throws Exception {
        var env=new StandardEnvironment();env.setActiveProfiles("local-read-gate");
        env.getPropertySources().addFirst(new MapPropertySource("fixed-fixture",Map.of(
                "spring.sql.init.mode","never","spring.jpa.hibernate.ddl-auto","none")));
        new FaucetStore(source,version,"verify-existing",env).initialize();
    }
}
