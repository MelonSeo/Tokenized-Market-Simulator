package com.pricetrack.exchange.ai.agent;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/** Additional conservative contradiction check, not a proof of arbitrary natural-language truth. */
final class BuyFeeMeaning {
    private BuyFeeMeaning(){}
    static boolean contradiction(String prose,JsonNode input){
        for(var t:input.path("tools")){
            var r=t.path("data").path("amountRelation");if(!r.path("inputIncludesFee").asBoolean())continue;
            for(String sentence:prose.split("(?<=[!?。！？\\n])|(?<=다\\.)")){
                String s=sentence.replaceAll("\\s+","").replace(",","");
                if(s.matches(".*수수료.{0,35}(추가|별도|다시)(로)?차감(됩|합|하겠).*"))return true;
                boolean negative=s.matches(".*(추가차감하지않|별도차감하지않|더하지않|더해지지않|합산하지않|아니|포함|추가비용없).*" );
                if(!negative&&s.matches(".*(수수료.{0,35}(추가|별도|더해|합산)|(입력|원금).{0,20}수수료.{0,20}(더해|더하|합산)).*"))return true;
                try{String wrong=new BigDecimal(r.path("quotedTotalDebit").asText()).add(new BigDecimal(r.path("includedFee").asText())).stripTrailingZeros().toPlainString();
                    if(!negative&&s.contains(wrong)&&s.matches(".*(차감|출금|지출|총액).*"))return true;
                }catch(NumberFormatException ignored){return true;}
            }
        }
        return false;
    }
}
