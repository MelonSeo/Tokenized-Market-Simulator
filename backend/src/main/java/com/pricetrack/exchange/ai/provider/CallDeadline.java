package com.pricetrack.exchange.ai.provider;

import com.pricetrack.exchange.ai.AiFailure;
import java.time.Duration;
import java.util.function.LongSupplier;

/** One monotonic budget, including preparation/serialization/decode. Never renew on a nested call. */
public final class CallDeadline {
    private final long end;private final LongSupplier clock;
    public CallDeadline(Duration budget){this(budget,System::nanoTime);}
    CallDeadline(Duration budget,LongSupplier clock){
        if(budget==null||budget.isZero()||budget.isNegative()||budget.compareTo(Duration.ofMinutes(10))>0)
            throw new AiFailure("AI_PROVIDER_UNAVAILABLE");
        this.clock=clock;end=clock.getAsLong()+budget.toNanos();
    }
    public Duration remaining(){
        long left=end-clock.getAsLong();
        if(left<=0||Thread.currentThread().isInterrupted())throw new AiFailure("AI_PROVIDER_UNAVAILABLE");
        return Duration.ofNanos(left);
    }
}
