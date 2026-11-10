package com.pricetrack.exchange.ai.pilot;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PilotV2FailureCodesTest {
 @Test void wrappedSafeCodeSurvivesWithoutExceptionMessage(){var e=new RuntimeException("SECRET_PASSWORD",new com.pricetrack.exchange.ai.AiFailure("AI_EVALUATION_BROKER_NOT_READY"));assertThat(PilotV2FailureCodes.chain(e).toString()).contains("AI_EVALUATION_BROKER_NOT_READY").doesNotContain("SECRET_PASSWORD");}
 @Test void arbitraryCodeCannotSmuggleSecrets(){assertThat(PilotV2FailureCodes.chain(new com.pricetrack.exchange.ai.AiFailure("bad sk-secret")).toString()).doesNotContain("sk-secret");}
 @Test void cyclicCauseIsBounded(){var a=new RuntimeException();var b=new RuntimeException(a);a.initCause(b);assertThat(PilotV2FailureCodes.chain(a)).hasSize(2);}
 public static void main(String[] args){var l=new org.junit.platform.launcher.listeners.SummaryGeneratingListener();org.junit.platform.launcher.core.LauncherFactory.create().execute(org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request().selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(PilotV2FailureCodesTest.class)).build(),l);var s=l.getSummary();s.printTo(new java.io.PrintWriter(System.out,true));s.printFailuresTo(new java.io.PrintWriter(System.out,true));System.exit(s.getTestsSucceededCount()==3&&s.getTestsSkippedCount()==0&&s.getTotalFailureCount()==0?0:1);}
}
