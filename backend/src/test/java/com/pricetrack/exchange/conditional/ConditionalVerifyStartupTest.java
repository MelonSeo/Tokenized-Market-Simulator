package com.pricetrack.exchange.conditional;
import com.pricetrack.exchange.common.schema.CatalogFixture;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConditionalVerifyStartupTest {
    private void initialize(CatalogFixture catalog) throws Exception {
        new ConditionalSchema(catalog.source(),"verify-existing",CatalogFixture.local()).initialize();
    }
    @Test void actualInitializerReadsExistingRequiredColumnsKeysAndIndexesOnly() throws Exception {
        var catalog=new CatalogFixture();initialize(catalog);
        assertFalse(catalog.sql.isEmpty());assertTrue(catalog.sql.stream().allMatch(s->s.stripLeading().startsWith("SELECT")));
    }
    @Test void absentWrongOrIncompleteRequiredSchemaFailsClosedWithoutInstall() {
        for(String bad:new String[]{"table","type","key","index","role"}) {
            var catalog=new CatalogFixture();
            switch(bad) {
                case "table"->catalog.columns.remove("conditional_orders");
                case "type"->catalog.columns.get("conditional_orders").get(4)[1]="numeric(30,18)";
                case "key"->catalog.keys.put("conditional_orders",List.of());
                case "index"->catalog.indexesPresent=false;
                case "role"->catalog.roleSafe=false;
            }
            assertThrows(IllegalStateException.class,()->initialize(catalog));
            assertTrue(catalog.sql.stream().allMatch(s->s.stripLeading().startsWith("SELECT")));
        }
    }
    @Test void verificationDoesNotPretendH2ProvesPostgresCatalogCompatibility() {
        var catalog=new CatalogFixture();catalog.product="H2";
        assertThrows(IllegalStateException.class,()->initialize(catalog));assertTrue(catalog.sql.isEmpty());
    }
}
