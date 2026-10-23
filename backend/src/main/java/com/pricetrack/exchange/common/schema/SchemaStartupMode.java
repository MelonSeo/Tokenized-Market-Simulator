package com.pricetrack.exchange.common.schema;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Startup responsibility only; never changes credit, policy or trading behavior. */
public enum SchemaStartupMode {
    INSTALL, VERIFY_EXISTING;

    public static SchemaStartupMode resolve(String value, Environment environment) {
        if ("install".equals(value)) return INSTALL;
        if ("verify-existing".equals(value) && environment != null
                && environment.acceptsProfiles(Profiles.of("local-read-gate"))
                && "never".equals(environment.getProperty("spring.sql.init.mode"))
                && "none".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto"))) return VERIFY_EXISTING;
        throw new IllegalArgumentException("SCHEMA_STARTUP_MODE_INVALID_OR_NOT_LOCAL");
    }
}
