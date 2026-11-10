package com.pricetrack.exchange.ai.pilot;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PilotV2AddressTest {
 @Test void pacingUsesTheBudgetWritersJvmClockAndPreservesSixtySecondWindow(){
  var start=java.time.Instant.parse("2026-11-04T00:00:00Z");
  assertThat(PilotV2Pacing.waitMillis(5,start,start.plusMillis(57_500))).isEqualTo(3000);
  assertThat(PilotV2Pacing.waitMillis(5,start,start.plusMillis(60_500))).isZero();
  assertThat(PilotV2Pacing.waitMillis(4,start,start)).isZero();
  assertThat(PilotV2Pacing.waitMillis(5,start,start.minusSeconds(2))).isEqualTo(62_500);
 }
 @Test void internalAliasRequiresAllThreeExplicitUnpaidLinuxConditions(){
  assertThat("pg:5432".matches(PilotV2TestConfiguration.jdbcHost("UNPAID_CONTROLLED_ONLY","true","Linux"))).isTrue();
  for(String[] flags:new String[][]{{"PAID_EXPLICIT_V2","true","Linux"},{"UNPAID_CONTROLLED_ONLY","false","Linux"},{"UNPAID_CONTROLLED_ONLY","true","Windows 11"}})assertThat("pg:5432".matches(PilotV2TestConfiguration.jdbcHost(flags[0],flags[1],flags[2]))).isFalse();
 }
 @Test void windowsLoopbackRemainsSupportedButForeignDnsAndPortsAreDenied(){
  String host=PilotV2TestConfiguration.jdbcHost("UNPAID_CONTROLLED_ONLY","true","Linux");
  assertThat("127.0.0.1:5432".matches(host)).isTrue();
  for(String bad:new String[]{"external.example:5432","pg:5433","localhost:5432","user@pg:5432","pg:5432/path"})assertThat(bad.matches(host)).isFalse();
 }
}
