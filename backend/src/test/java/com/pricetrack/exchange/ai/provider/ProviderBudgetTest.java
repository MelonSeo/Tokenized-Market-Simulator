package com.pricetrack.exchange.ai.provider;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
class ProviderBudgetTest {
    final ObjectMapper json=new ObjectMapper();
    byte[] wire(String model,String tier,int output)throws Exception{return json.writeValueAsBytes(Map.of("model",model,"service_tier",tier,"store",false,"max_output_tokens",output,"input","질문"));}
    @Test void fullUtf8EnvelopeAndMaximumOutputAreReserved()throws Exception{var b=wire("gpt-5.6-terra","default",1600);var e=PgProviderBudget.estimate("responses",b);assertThat(e.inputTokens()).isEqualTo(b.length+1024);assertThat(e.reservedNusd()).isEqualTo((b.length+1024L)*2500+1600*12000L);}
    @Test void unknownModelsTiersAndOutputCannotBypassPricing()throws Exception{
        for(byte[] b:List.of(wire("other-model","default",1600),wire("gpt-5.6-terra","priority",1600),wire("gpt-5.6-terra","default",1601),wire("gpt-5.6-terra","default",0)))assertThatThrownBy(()->PgProviderBudget.estimate("responses",b)).hasMessage("AI_BUDGET_CONFIG_INVALID");
    }
    @Test void largeContextCannotReachSurchargeTier(){assertThatThrownBy(()->PgProviderBudget.estimate("responses",new byte[65537])).hasMessage("AI_BUDGET_INPUT_LIMIT");}
    @Test void noImplicitApprovalOrActor(){assertThatThrownBy(()->ProviderBudget.DENY.reserve("responses",new byte[0])).hasMessage("AI_BUDGET_NOT_CONFIGURED");assertThatThrownBy(ProviderAccess::actor).hasMessage("AI_BUDGET_ACTOR_REQUIRED");}
    @Test void actorScopeRestoresAfterFailure(){assertThatThrownBy(()->ProviderAccess.call(2,UUID.randomUUID().toString(),()->{assertThat(ProviderAccess.actor().userId()).isEqualTo(2);throw new IllegalArgumentException();})).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(ProviderAccess::actor).hasMessage("AI_BUDGET_ACTOR_REQUIRED");}
}
