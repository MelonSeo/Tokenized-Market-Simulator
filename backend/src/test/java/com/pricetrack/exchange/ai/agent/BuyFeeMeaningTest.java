package com.pricetrack.exchange.ai.agent;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.tool.read.BuyAmountRelation;
import com.pricetrack.exchange.ai.tool.ToolFixtures;
import org.junit.jupiter.api.Test;
class BuyFeeMeaningTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void relationshipUsesOriginalWeiFloorWithoutChangingInput(){
        var q=ToolFixtures.quote(1,2);var n=BuyAmountRelation.quoted(json,q);
        assertThat(n.path("quotedTotalDebit").asText()).isEqualTo("75000.000000000000000000");assertThat(n.path("includedFee").asText()).isEqualTo("75.000000000000000000");
        assertThat(n.path("netPricingInput").asText()).isEqualTo("74925.000000000000000000");assertThat(n.path("calculatedOutput").asText()).isEqualTo("0.999000000000000000");assertThat(n.path("additionalFeeDebit").asText()).isEqualTo("0");
    }
    @Test void explicitWrongNaturalRelationRejectedButNegationAllowed(){
        var input=json.createObjectNode();input.putArray("tools").addObject().putObject("data").set("amountRelation",BuyAmountRelation.quoted(json,ToolFixtures.quote(1,2)));
        for(String wrong:new String[]{"수수료 75가 별도로 차감됩니다.","총 75075 mKRW가 차감됩니다.","수수료는 포함되지만 추가 차감됩니다."})assertThat(BuyFeeMeaning.contradiction(wrong,input)).isTrue();
        for(String good:new String[]{"수수료는 입력 금액에 포함됩니다. 추가 차감하지 않습니다.","75075가 아니라 입력 총액 75000만 차감됩니다."})assertThat(BuyFeeMeaning.contradiction(good,input)).isFalse();
    }
    @Test void invalidQuotedFeeCannotBecomeVerifiedRelation(){var q=ToolFixtures.quote(1,2);q.setFee(q.getInputAmount().add(java.math.BigInteger.ONE));assertThat(BuyAmountRelation.quoted(json,q).path("inputIncludesFee").asBoolean()).isFalse();}
}
