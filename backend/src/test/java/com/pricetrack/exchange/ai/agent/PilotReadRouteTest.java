package com.pricetrack.exchange.ai.agent;
import static org.assertj.core.api.Assertions.*;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
class PilotReadRouteTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void currentProviderAndVolumePhrasesRequireActualRead(){
        for(String q:new String[]{"현재 차트의 거래량은 누가 만든 수치인가요?","지금 provider가 뭐고 volume은 실제 체결량인가요?","여기 거래량은 왜 올라가나요?","화면의 합성시세 출처와 거래량을 알려줘"}){
            var p=RequiredReadRoute.select(new AgentRequest(q,null,null));assertThat(p.route()).isEqualTo(Route.MIXED);assertThat(p.subject()).isEqualTo(Subject.PRICE);
        }
    }
    @Test void policyOnlyRequestDoesNotForceCurrentRead(){assertThat(RequiredReadRoute.select(new AgentRequest("문서에서 Synthetic 거래량의 용어 정의만 설명해줘",null,null))).isNull();}
    @Test void explicitWaitingFilterIsNotSilentlyDefaulted(){
        var r=AgentRequest.parse(json,"{\"question\":\"장기대기 주문 목록\",\"abnormal\":{\"filter\":\"WAITING_LONG\",\"limit\":7,\"waitingSeconds\":120}}".getBytes(StandardCharsets.UTF_8));
        assertThat(RequiredReadRoute.select(r).subject()).isEqualTo(Subject.ABNORMAL);assertThat(RequiredReadRoute.abnormal(r)).containsEntry("filter","WAITING_LONG").containsEntry("limit",7).containsEntry("waitingSeconds",120);
    }
    @Test void ambiguousComparisonDoesNotInventWaitingHistory(){var r=new AgentRequest("오래 기다린 주문 목록을 봤는데 REVIEW_REQUIRED 이상 목록이 전부인가요?",null,null);assertThat(RequiredReadRoute.abnormal(r)).containsEntry("filter","REVIEW_REQUIRED");}
    @Test void boundedNaturalNumbersAndExplicitUiHaveSameMeaning(){var r=new AgentRequest("WAITING_LONG 앞 5건을 120초 기준으로 조회",null,null);assertThat(RequiredReadRoute.abnormal(r)).containsEntry("filter","WAITING_LONG").containsEntry("limit",5).containsEntry("waitingSeconds",120);}
    @Test void ambiguousOrUnsupportedBoundsAreNotSilentlyDefaulted(){for(String q:new String[]{"이상 주문 5건 또는 10건","WAITING_LONG 21건","대기 10초","대기 9999999999999999999초"})assertThatThrownBy(()->RequiredReadRoute.abnormal(new AgentRequest(q,null,null))).hasMessage("ABNORMAL_FILTER_CLARIFICATION_REQUIRED");}
    @Test void abnormalInputCannotChooseOwnerOrUnboundedLimit(){for(String bad:new String[]{"{\"userId\":2}","{\"limit\":21}","{\"waitingSeconds\":1}"})assertThatThrownBy(()->AgentRequest.parse(json,("{\"question\":\"목록\",\"abnormal\":"+bad+"}").getBytes(StandardCharsets.UTF_8))).isInstanceOf(AgentFailure.class);}
    @Test void cTargetIsNotLegacyUuidOrQuote(){String id="0x"+"ab".repeat(32);var r=AgentRequest.parse(json,("{\"question\":\"이 포지션 "+id+" 조회\",\"target\":{\"cPositionId\":\""+id+"\"}}").getBytes(StandardCharsets.UTF_8));assertThat(r.targetKind()).isEqualTo(Subject.C_POSITION);assertThat(r.conflictingTarget()).isFalse();assertThat(RequiredReadRoute.select(r).route()).isEqualTo(Route.STATE);}
    @Test void missingTargetHashIsNeverInvented(){var r=new AgentRequest("C 포지션 0x"+"ab".repeat(32)+" 조회",null,null);assertThat(r.conflictingTarget()).isTrue();}
}
