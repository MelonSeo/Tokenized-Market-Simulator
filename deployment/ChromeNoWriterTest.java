package com.pricetrack.exchange.futures.chain;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
public class ChromeNoWriterTest {
 @Test void onlyExactForeignResourceDenialPasses(){var j=new com.fasterxml.jackson.databind.ObjectMapper();var body=j.createObjectNode().put("status","ERROR").put("error","RESOURCE_NOT_FOUND").putNull("data");PilotChromeResume.verifyForeignQuote(404,body);}
 @Test void providerFailureOrTimeoutIsNotAuthorizationEvidence(){var j=new com.fasterxml.jackson.databind.ObjectMapper();for(int status:new int[]{503,504,500}){var body=j.createObjectNode().put("status","ERROR").put("error","TOOL_TIMEOUT").putNull("data");assertThatThrownBy(()->PilotChromeResume.verifyForeignQuote(status,body)).isInstanceOf(AssertionError.class);}}
 @Test void actualWorkerIsClosedBeforeListenerCanSignalAndScheduledStartIsRemoved(){
  var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();var definition=new org.springframework.beans.factory.support.RootBeanDefinition(com.pricetrack.exchange.futures.runtime.FuturesWorker.class);definition.setInitMethodName("start");factory.registerBeanDefinition("futuresWorker",definition);
  PilotChromeResume.NoWriter.freezeWorker().postProcessBeanFactory(factory);assertThat(definition.getInitMethodName()).isNull();
  var worker=new com.pricetrack.exchange.futures.runtime.FuturesWorker(null,null,null,null,null,null,null);
  assertThat(PilotChromeResume.NoWriter.closeWorkerBeforeExposure().postProcessBeforeInitialization(worker,"futuresWorker")).isSameAs(worker);
  worker.signal();var executor=(java.util.concurrent.ExecutorService)org.springframework.test.util.ReflectionTestUtils.getField(worker,"executor");assertThat(executor.isShutdown()).isTrue();assertThat(worker.failureCount()).isZero();
 }
 public static void main(String[] args){var l=new org.junit.platform.launcher.listeners.SummaryGeneratingListener();org.junit.platform.launcher.core.LauncherFactory.create().execute(org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request().selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(ChromeNoWriterTest.class)).build(),l);var s=l.getSummary();s.printTo(new java.io.PrintWriter(System.out,true));s.printFailuresTo(new java.io.PrintWriter(System.out,true));System.exit(s.getTestsSucceededCount()==3&&s.getTestsSkippedCount()==0&&s.getTotalFailureCount()==0?0:1);}
}
