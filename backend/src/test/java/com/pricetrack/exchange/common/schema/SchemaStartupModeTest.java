package com.pricetrack.exchange.common.schema;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class SchemaStartupModeTest {
    @Test void installRemainsCompatibleWithoutLocalProfileOrSpecialSettings() {
        assertEquals(SchemaStartupMode.INSTALL,SchemaStartupMode.resolve("install",new MockEnvironment()));
    }
    @Test void verifyRequiresLocalProfileAndExplicitDisabledAutomaticDdl() {
        assertEquals(SchemaStartupMode.VERIFY_EXISTING,SchemaStartupMode.resolve("verify-existing",CatalogFixture.local()));
        assertThrows(IllegalArgumentException.class,()->SchemaStartupMode.resolve("verify-existing",new MockEnvironment()));
        for(String key:new String[]{"spring.sql.init.mode","spring.jpa.hibernate.ddl-auto"}) {
            var env=CatalogFixture.local().withProperty(key,key.contains("sql")?"always":"validate");
            assertThrows(IllegalArgumentException.class,()->SchemaStartupMode.resolve("verify-existing",env));
        }
    }
    @Test void unknownBlankOrWrongCaseModesFailClosed() {
        for(String mode:new String[]{"","unknown","VERIFY_EXISTING","Install"})assertThrows(IllegalArgumentException.class,()->SchemaStartupMode.resolve(mode,CatalogFixture.local()));
    }
}
