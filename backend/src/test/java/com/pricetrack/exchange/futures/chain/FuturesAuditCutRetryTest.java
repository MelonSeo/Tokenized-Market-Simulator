package com.pricetrack.exchange.futures.chain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pricetrack.exchange.reserve.ReserveModels;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Offline checks for the fixture-only bounded observation loop, not a product reconciliation policy. */
class FuturesAuditCutRetryTest {
    ReserveModels.Result result(String status,String reason) {
        var value=mock(ReserveModels.Result.class);
        when(value.status()).thenReturn(status);when(value.reason()).thenReturn(reason);
        return value;
    }
    @Test void stableMatchIsReturnedWithoutAnotherRead()throws Exception {
        var match=result("MATCH","EXACT_QUANTITY_MATCH");var calls=new AtomicInteger();
        assertThat(FuturesAuditPostgresAnvilE2ETest.stableReserve(()->{calls.incrementAndGet();return match;})).isSameAs(match);
        assertThat(calls).hasValue(1);
    }
    @Test void cutChangesCanBeFollowedByAnActualMismatchButNeverPromoted()throws Exception {
        var moving=result("INCONCLUSIVE","CUT_CHANGED");var mismatch=result("MISMATCH","UNEXPLAINED_QUANTITY_OR_CONTRACT_DIFFERENCE");
        var calls=new AtomicInteger();
        assertThat(FuturesAuditPostgresAnvilE2ETest.stableReserve(()->calls.incrementAndGet()<3?moving:mismatch)).isSameAs(mismatch);
        assertThat(calls).hasValue(3);
    }
    @Test void otherInconclusiveAndUnavailableReasonsAreNotRetried()throws Exception {
        for(String reason:new String[]{"UNSETTLED_FUTURES_OR_REVIEW","DEPENDENCY_READ_FAILED"}) {
            var unknown=result(reason.startsWith("DEPENDENCY")?"UNAVAILABLE":"INCONCLUSIVE",reason);var calls=new AtomicInteger();
            assertThat(FuturesAuditPostgresAnvilE2ETest.stableReserve(()->{calls.incrementAndGet();return unknown;})).isSameAs(unknown);
            assertThat(calls).hasValue(1);
        }
    }
    @Test void fourMovingCutsRemainInconclusiveAndNeverCauseAFifthRead()throws Exception {
        var moving=result("INCONCLUSIVE","CUT_CHANGED");var calls=new AtomicInteger();
        var observed=FuturesAuditPostgresAnvilE2ETest.stableReserve(()->{calls.incrementAndGet();return moving;});
        assertThat(observed).isSameAs(moving);assertThat(observed.status()).isEqualTo("INCONCLUSIVE");assertThat(calls).hasValue(4);
    }
}
