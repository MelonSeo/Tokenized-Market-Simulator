package com.pricetrack.exchange.ai.pilot;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PilotV2PacingTest {
 static final class Time extends Clock {
  Instant now=Instant.parse("2026-11-04T00:00:00Z");long ns;int sleeps;boolean rollback;
  public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){if(!z.equals(ZoneOffset.UTC))throw new IllegalArgumentException();return this;}public Instant instant(){return now;}
  void sleep(long ms){sleeps++;ns+=ms*1_000_000;now=now.plusMillis(ms);if(rollback&&sleeps==1)now=now.minusMillis(2384);}
 }
 @Test void rechecksAfterSleepInsteadOfAdmittingWhileTheSixtySecondWindowIsStillFull()throws Exception{
  var t=new Time();t.rollback=true;Instant oldest=t.now;var events=new ArrayList<PilotV2Pacing.Observation>();
  PilotV2Pacing.await(()->new PilotV2Pacing.Window(5,oldest,t.now),t,()->t.ns,t::sleep,events::add);
  assertThat(t.sleeps).isEqualTo(2);assertThat(Duration.between(oldest,t.now).toMillis()).isEqualTo(60501);
  assertThat(events.stream().filter(e->e.stage().equals("CHECK")).map(PilotV2Pacing.Observation::waitMillis).toList()).containsExactly(60500L,2383L,0L);
 }
 @Test void ordinaryWindowWaitsOnceAndDoesNotCountProviderCallsAsSeparateRequests()throws Exception{
  var t=new Time();Instant oldest=t.now;
  PilotV2Pacing.await(()->new PilotV2Pacing.Window(5,oldest,t.now),t,()->t.ns,t::sleep,e->{});
  assertThat(t.sleeps).isEqualTo(1);assertThat(PilotV2Pacing.waitMillis(4,oldest,t.now)).isZero();
 }
 @Test void emptyWindowAndExactConservativeBoundaryAreAdmittedWithoutSleep()throws Exception{
  var t=new Time();Instant oldest=t.now.minusMillis(60500);
  PilotV2Pacing.await(()->new PilotV2Pacing.Window(5,oldest,t.now),t,()->t.ns,t::sleep,e->{});
  PilotV2Pacing.await(()->new PilotV2Pacing.Window(0,Instant.EPOCH,t.now),t,()->t.ns,t::sleep,e->{});
  assertThat(t.sleeps).isZero();
 }
 @Test void repeatedClockRollbackCannotProduceAnUnboundedWait(){
  var t=new Time();Instant oldest=t.now;
  assertThatThrownBy(()->PilotV2Pacing.await(()->new PilotV2Pacing.Window(5,oldest,t.now),t,()->t.ns,ms->{t.sleep(ms);t.now=oldest;},e->{})).hasMessage("V2_PACING_DEADLINE");
  assertThat(t.ns).isLessThan(Duration.ofSeconds(150).toNanos());
 }
 @Test void readFailureAndCancellationDoNotAdmitOrRetryARequest(){
  var t=new Time();assertThatThrownBy(()->PilotV2Pacing.await(()->{throw new IllegalStateException("CONTROLLED_READ_FAILURE");},t,()->t.ns,t::sleep,e->{})).hasMessage("CONTROLLED_READ_FAILURE");
  assertThatThrownBy(()->PilotV2Pacing.await(()->new PilotV2Pacing.Window(5,t.now,t.now),t,()->t.ns,ms->{throw new InterruptedException("CONTROLLED_CANCEL");},e->{})).isInstanceOf(InterruptedException.class);
  assertThat(t.sleeps).isZero();
 }
 @Test void inclusiveSixtySecondCutoffKeepsTheExistingHalfSecondSafetyMargin(){
  var t=new Time();assertThat(PilotV2Pacing.waitMillis(5,t.now,t.now.plusSeconds(60))).isEqualTo(500);
  assertThat(PilotV2Pacing.waitMillis(5,t.now,t.now.plusMillis(60499))).isEqualTo(1);
  assertThat(PilotV2Pacing.waitMillis(5,t.now.plusSeconds(2),t.now)).isEqualTo(62500);
 }
 @Test void readingOrRecordingCannotOverrunTheBoundAndStillAdmit(){
  var t=new Time();assertThatThrownBy(()->PilotV2Pacing.await(()->new PilotV2Pacing.Window(0,Instant.EPOCH,t.now),t,()->t.ns,t::sleep,e->t.ns=Duration.ofSeconds(150).toNanos())).hasMessage("V2_PACING_DEADLINE");
 }
}
