package com.pricetrack.exchange.ai.evaluation;

import static org.assertj.core.api.Assertions.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class EvaluationFailureDiagnosticTest {
    @Test void sqlStateWithoutMessageOrCredential() {
        var safe=EvaluationFailureDiagnostic.safe(new IllegalStateException("secret-token",new SQLException("password=private","08001",42)));
        assertThat(safe).containsEntry("sqlState","08001").containsEntry("vendorCode",42);
        assertThat(safe.toString()).doesNotContain("secret-token","password","private","SQLException");
    }
    @Test void maliciousStateIsNotLogged() {
        assertThat(EvaluationFailureDiagnostic.safe(new SQLException("private","secret-string")))
            .containsEntry("sqlState","UNAVAILABLE");
    }
    @Test void nonSqlFailureIsFixedCategoryOnly() {
        assertThat(EvaluationFailureDiagnostic.safe(new RuntimeException("private"))).containsOnlyKeys("category");
    }
    @Test void cyclicCauseTerminates() {
        var a=new RuntimeException();var b=new RuntimeException(a);a.initCause(b);
        assertThat(EvaluationFailureDiagnostic.safe(a)).containsOnlyKeys("category");
    }
}
