package com.pricetrack.exchange.ai.agent;
import static org.assertj.core.api.Assertions.*;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;
import org.junit.jupiter.api.Test;
class DeterministicStateRouteTest {
    @Test void measuredBoundedDefaultsAndExplicitLegacyProfile(){
        var p=new AgentProperties(true);assertThat(p.planningSeconds()).isEqualTo(15);assertThat(p.synthesisSeconds()).isEqualTo(30);
        assertThat(p.totalSeconds()).isEqualTo(90);assertThat(p.retrievalSeconds()).isEqualTo(15);assertThat(p.deterministicState()).isTrue();
        assertThat(new AgentProperties(true,5,10,5,40,false).totalSeconds()).isEqualTo(40);
        assertThat(new AgentProperties(true,15,30,15,90,false).totalSeconds()).isEqualTo(90);
        assertThatThrownBy(()->new AgentProperties(true,15,30,15,20,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new AgentProperties(true,0,30,15,90,false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void springBindingUsesMeasuredDefaultsWithoutEnablingAgent(){
        var env=new org.springframework.mock.env.MockEnvironment();
        var bound=org.springframework.boot.context.properties.bind.Binder.get(env).bindOrCreate("app.ai.agent",AgentProperties.class);
        assertThat(bound).isEqualTo(new AgentProperties(false));assertThat(bound.enabled()).isFalse();
        env.withProperty("app.ai.agent.planning-seconds","5").withProperty("app.ai.agent.synthesis-seconds","10")
            .withProperty("app.ai.agent.retrieval-seconds","5").withProperty("app.ai.agent.total-seconds","40").withProperty("app.ai.agent.deterministic-state","false");
        assertThat(org.springframework.boot.context.properties.bind.Binder.get(env).bindOrCreate("app.ai.agent",AgentProperties.class))
            .isEqualTo(new AgentProperties(false,5,10,5,40,false));
    }
    @Test void closedGrammarSupportsUnseenWordingButNotMixedIntent(){
        assertThat(DeterministicStateRoute.select(new AgentRequest("지금 기준시세와 시장상태 보여줘",null,null)).subject()).isEqualTo(Subject.PRICE);
        assertThat(DeterministicStateRoute.select(new AgentRequest("내 가용 잔고와 포트폴리오를 조회해 주세요.",null,null)).subject()).isEqualTo(Subject.PORTFOLIO);
        assertThat(DeterministicStateRoute.select(new AgentRequest("내 주문의 현재 상태만 조회해 주세요.",1L,null)).subject()).isEqualTo(Subject.ORDER);
        for(String q:new String[]{"현재 기준가격과 시장 상태를 조회하고 정책도 설명해줘","내 가용 잔고가 감소한 이유는?","주문을 만들어줘","이 청산은 확정됐나요?"})assertThat(DeterministicStateRoute.select(new AgentRequest(q,null,null))).isNull();
        assertThat(DeterministicStateRoute.select(new AgentRequest("내 주문의 현재 상태만 조회해 주세요.",null,null))).isNull();
    }
}
