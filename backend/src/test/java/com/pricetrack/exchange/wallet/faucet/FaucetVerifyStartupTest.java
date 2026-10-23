package com.pricetrack.exchange.wallet.faucet;
import com.pricetrack.exchange.common.schema.CatalogFixture;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FaucetVerifyStartupTest {
    private void initialize(CatalogFixture catalog) throws Exception {
        new FaucetStore(catalog.source(),"kst-v1","verify-existing",CatalogFixture.local()).initialize();
    }
    @Test void actualStoreVerifiesPreinstalledCatalogWithOnlySelectAndNeverActivatesOrRepairs() throws Exception {
        var catalog=new CatalogFixture();initialize(catalog);
        assertFalse(catalog.sql.isEmpty());assertTrue(catalog.sql.stream().allMatch(s->s.stripLeading().startsWith("SELECT")));
        assertTrue(catalog.sql.stream().anyMatch(s->s.contains("b.initialized=TRUE")&&s.contains("p.content_hash=?")&&s.contains("p.content=?")));
    }
    @Test void unsafeRoleOrMissingOrChangedColumnsFailClosed() throws Exception {
        var role=new CatalogFixture();role.roleSafe=false;assertThrows(IllegalStateException.class,()->initialize(role));
        var missing=new CatalogFixture();missing.columns.remove("faucet_claims");assertThrows(IllegalStateException.class,()->initialize(missing));
        var changed=new CatalogFixture();changed.columns.get("faucet_budget_usage").get(3)[1]="numeric(10,2)";assertThrows(IllegalStateException.class,()->initialize(changed));
    }
    @Test void missingOrWrongFunctionBodyFailsClosed() {
        for(String body:new String[]{null," BEGIN RETURN NULL; END "," BEGIN RAISE EXCEPTION 'different'; END "}) {
            var catalog=new CatalogFixture();catalog.body=body;assertThrows(IllegalStateException.class,()->initialize(catalog));
        }
    }
    @Test void disabledRowLevelWrongFunctionOrMissingTriggerFailsClosed() {
        for(String bad:new String[]{"disabled","row","function","missing"}) {
            var catalog=new CatalogFixture();
            switch(bad) {case "disabled"->catalog.triggerEnabled="D";case "row"->catalog.triggerType=59;case "function"->catalog.triggerFunction="different";case "missing"->catalog.triggerPresent=false;}
            assertThrows(IllegalStateException.class,()->initialize(catalog));
        }
    }
    @Test void missingInitializedFalseOrWrongVersionPolicyAndBudgetConflictsNeverGetRepaired() {
        for(String bad:new String[]{"policy","budget","journal"}) {
            var catalog=new CatalogFixture();
            switch(bad) {case "policy"->catalog.configured=false;case "budget"->catalog.budgetRows=2;case "journal"->catalog.consistent=false;}
            assertThrows(IllegalStateException.class,()->initialize(catalog));
            assertTrue(catalog.sql.stream().allMatch(s->s.stripLeading().startsWith("SELECT")));
        }
    }
    @Test void missingKeysAndNonPostgresVerificationAreRejected() throws Exception {
        var keys=new CatalogFixture();keys.keys.put("faucet_claims",List.of());assertThrows(IllegalStateException.class,()->initialize(keys));
        var h2=new CatalogFixture();h2.product="H2";assertThrows(IllegalStateException.class,()->initialize(h2));assertTrue(h2.sql.isEmpty());
    }
    @Test void grantsApiOwnershipMissingOriginalColumnsOrPrimaryKeyFailClosed() {
        for(String bad:new String[]{"owner","column","primary-key"}) {
            var catalog=new CatalogFixture();
            switch(bad) {
                case "owner"->catalog.apiOwnedTables.add("faucet_grants");
                case "column"->catalog.columns.get("faucet_grants").remove(2);
                case "primary-key"->catalog.keys.put("faucet_grants",List.of());
            }
            assertThrows(IllegalStateException.class,()->initialize(catalog));
            assertTrue(catalog.sql.stream().anyMatch(s->s.contains("pg_catalog.pg_attribute")&&s.contains("c.relowner<>(SELECT oid FROM pg_roles WHERE rolname=current_user)")));
            assertTrue(catalog.sql.stream().allMatch(s->s.stripLeading().startsWith("SELECT")));
        }
    }
}
