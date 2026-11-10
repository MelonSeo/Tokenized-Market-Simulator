package com.pricetrack.exchange.ai.pilot;
import java.time.*;
import java.sql.*;
import java.util.function.*;
/** Compare request timestamps with their originating JVM clock, not PostgreSQL's independent clock. */
public final class PilotV2Pacing {
 private PilotV2Pacing(){}
 public static long waitMillis(int count,Instant oldest,Instant now){return count>=5?Math.max(0,60_500-Duration.between(oldest,now).toMillis()):0;}
 public record Window(int count,Instant oldest,Instant databaseUtc){}
 public record Observation(String stage,long monotonicElapsedNs,Instant jvmUtc,Instant databaseUtc,int count,Instant oldest,long waitMillis){}
 @FunctionalInterface public interface WindowReader {Window read()throws Exception;}
 @FunctionalInterface public interface Sleeper {void sleep(long millis)throws InterruptedException;}
 @FunctionalInterface public interface Recorder {void record(Observation event)throws Exception;}
 /** Read-only, same actor/request table as PgProviderBudget. PostgreSQL UTC is observation, NOT the authority clock. */
 public static Window read(Connection c,long actor)throws SQLException{
  try(var q=c.prepareStatement("select t.created_at,clock_timestamp() from (select 1) s left join lateral (select created_at from ai_provider_budget.request where actor=? order by created_at desc limit 5) t on true")){
   q.setQueryTimeout(5);q.setLong(1,actor);int count=0;Instant oldest=Instant.EPOCH,db=null;
   try(var r=q.executeQuery()){while(r.next()){db=r.getTimestamp(2).toInstant();var at=r.getTimestamp(1);if(at!=null){count++;oldest=at.toInstant();}}}
   return new Window(count,oldest,db);
  }
 }
 /** A sleep is not proof that a wall-clock quota expired. Re-read before admitting the next request. No send/retry here. */
 public static void await(WindowReader reader,Clock clock,LongSupplier nano,Sleeper sleeper,Recorder recorder)throws Exception{
  long start=nano.getAsLong(),limit=Duration.ofSeconds(150).toNanos();
  while(true){
   if(Thread.currentThread().isInterrupted())throw new InterruptedException("V2_PACING_INTERRUPTED");
   long elapsed=nano.getAsLong()-start;if(elapsed<0||elapsed>=limit)throw new IllegalStateException("V2_PACING_DEADLINE");
   var w=reader.read();Instant now=clock.instant();long delay=waitMillis(w.count,w.oldest,now);
   recorder.record(new Observation("CHECK",nano.getAsLong()-start,now,w.databaseUtc,w.count,w.oldest,delay));
   if(nano.getAsLong()-start>=limit)throw new IllegalStateException("V2_PACING_DEADLINE");
   if(delay==0)return;
   long remaining=(limit-(nano.getAsLong()-start))/1_000_000;
   if(delay>=remaining)throw new IllegalStateException("V2_PACING_DEADLINE");
   sleeper.sleep(delay+1);
   recorder.record(new Observation("WAKE",nano.getAsLong()-start,clock.instant(),null,w.count,w.oldest,delay));
  }
 }
}
