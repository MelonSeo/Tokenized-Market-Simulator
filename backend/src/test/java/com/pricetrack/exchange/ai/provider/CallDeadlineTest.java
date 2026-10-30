package com.pricetrack.exchange.ai.provider;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
class CallDeadlineTest {
    @Test void preparationAndDecodeConsumeOriginalBudget(){
        var now=new AtomicLong();var d=new CallDeadline(Duration.ofSeconds(10),now::get);
        now.set(280_000_000);assertThat(d.remaining()).isEqualTo(Duration.ofMillis(9720));
        now.set(9_999_000_000L);assertThat(d.remaining()).isEqualTo(Duration.ofMillis(1));
        now.set(10_000_000_000L);assertThatThrownBy(d::remaining).hasMessage("AI_PROVIDER_UNAVAILABLE");
    }
    @Test void invalidAndInterruptedBudgetsFailClosed(){
        assertThatThrownBy(()->new CallDeadline(Duration.ZERO)).hasMessage("AI_PROVIDER_UNAVAILABLE");
        Thread.currentThread().interrupt();try{assertThatThrownBy(()->new CallDeadline(Duration.ofSeconds(1)).remaining()).hasMessage("AI_PROVIDER_UNAVAILABLE");}
        finally{Thread.interrupted();}
    }
}
