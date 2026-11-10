package com.pricetrack.exchange.ai.pilot;
import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
class ReceiptProbeTraceTest {
 @Test void traceWriteFailureStillChecksFinancialInvariant(){
  var checked=new AtomicBoolean();var error=new IOException("diagnostic write");
  assertThatThrownBy(()->ReceiptProbeTrace.finalizeEvidence(()->{throw error;},()->checked.set(true))).isSameAs(error);
  assertThat(checked).isTrue();
 }
 @Test void bothFailuresRetainFinancialFailureAndDiagnosticEvidence(){
  var trace=new IOException("trace");var finance=new AssertionError("financial changed");
  assertThatThrownBy(()->ReceiptProbeTrace.finalizeEvidence(()->{throw trace;},()->{throw finance;})).isSameAs(finance);
  assertThat(finance.getSuppressed()).containsExactly(trace);
 }
 @Test void financialFailureIsNeverSwallowed(){
  var error=new AssertionError("financial changed");
  assertThatThrownBy(()->ReceiptProbeTrace.finalizeEvidence(()->{},()->{throw error;})).isSameAs(error);
 }
 public static void main(String[] args){var l=new org.junit.platform.launcher.listeners.SummaryGeneratingListener();org.junit.platform.launcher.core.LauncherFactory.create().execute(org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request().selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(ReceiptProbeTraceTest.class)).build(),l);var s=l.getSummary();s.printTo(new java.io.PrintWriter(System.out,true));s.printFailuresTo(new java.io.PrintWriter(System.out,true));System.exit(s.getTestsSucceededCount()==3&&s.getTestsSkippedCount()==0&&s.getTotalFailureCount()==0?0:1);}
}
