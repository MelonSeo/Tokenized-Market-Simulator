package com.pricetrack.exchange.ai.pilot;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
/** Fixture-only wakeups of the existing worker; no commands, prices or policy bypass. */
public final class PilotFixtureWait {
 public static final class Failure extends IllegalStateException {private final String code;public Failure(String code){super(code);this.code=code;}public String code(){return code;}}
 @FunctionalInterface public interface Sleep{void sleep(long ms)throws Exception;}
 public static void await(String code,Duration budget,BooleanSupplier done,Runnable wake,Runnable sample,LongSupplier clock,Sleep sleep)throws Exception{
  long start=clock.getAsLong(),end=start+budget.toNanos(),nextSample=start;
  while(true){
   long now=clock.getAsLong();if(now>=end)throw new Failure(code);
   if(done.getAsBoolean()){if(clock.getAsLong()>=end)throw new Failure(code);return;}
   if(now>=nextSample){sample.run();nextSample=now+TimeUnit.SECONDS.toNanos(5);}
   now=clock.getAsLong();if(now>=end)throw new Failure(code);
   wake.run();sleep.sleep(Math.min(250,Math.max(1,TimeUnit.NANOSECONDS.toMillis(end-now))));
  }
 }
}
