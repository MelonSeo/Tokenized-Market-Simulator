package com.pricetrack.exchange.ai.pilot;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
class PilotFixtureWaitTest {
 @Test void workerIsWokenUntilRealConditionIsSatisfied()throws Exception{var calls=new AtomicInteger();var clock=new AtomicLong();PilotFixtureWait.await("FIXTURE_TIMEOUT",Duration.ofSeconds(1),()->calls.get()==2,calls::incrementAndGet,()->{},clock::get,ms->clock.addAndGet(ms*1000000));assertThat(calls).hasValue(2);}
 @Test void wakeupsCannotExtendAbsoluteDeadline(){var calls=new AtomicInteger();var clock=new AtomicLong();assertThatThrownBy(()->PilotFixtureWait.await("FIXTURE_TIMEOUT",Duration.ofSeconds(1),()->false,calls::incrementAndGet,()->{},clock::get,ms->clock.addAndGet(ms*1000000))).isInstanceOf(PilotFixtureWait.Failure.class).hasMessage("FIXTURE_TIMEOUT");assertThat(calls).hasValue(4);assertThat(clock).hasValue(1000000000L);}
 @Test void completedFixtureNeverTriggersAnotherWakeup()throws Exception{PilotFixtureWait.await("FIXTURE_TIMEOUT",Duration.ofSeconds(1),()->true,()->{throw new AssertionError("unexpected wake");},()->{throw new AssertionError("unexpected sample");},()->0,ms->{});}
 @Test void diagnosticCannotBorrowTimeOrWakeAfterDeadline(){var clock=new AtomicLong();var wakes=new AtomicInteger();assertThatThrownBy(()->PilotFixtureWait.await("FIXTURE_TIMEOUT",Duration.ofSeconds(1),()->false,wakes::incrementAndGet,()->clock.set(2000000000L),clock::get,ms->{})).isInstanceOf(PilotFixtureWait.Failure.class);assertThat(wakes).hasValue(0);}
 public static void main(String[] args){var l=new org.junit.platform.launcher.listeners.SummaryGeneratingListener();org.junit.platform.launcher.core.LauncherFactory.create().execute(org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request().selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(PilotFixtureWaitTest.class)).build(),l);var s=l.getSummary();s.printTo(new java.io.PrintWriter(System.out,true));s.printFailuresTo(new java.io.PrintWriter(System.out,true));System.exit(s.getTestsSucceededCount()==4&&s.getTestsSkippedCount()==0&&s.getTotalFailureCount()==0?0:1);}
}
