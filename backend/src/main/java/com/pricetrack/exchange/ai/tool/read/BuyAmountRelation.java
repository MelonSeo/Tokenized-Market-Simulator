package com.pricetrack.exchange.ai.tool.read;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.quote.PriceQuote;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import java.math.BigInteger;

/** Observation-only arithmetic; does not calculate fees, write balances, or attest execution. */
public final class BuyAmountRelation {
    private BuyAmountRelation(){}
    public static ObjectNode quoted(ObjectMapper json,PriceQuote q){
        var input=q.getInputAmount();var fee=q.getFee();var price=q.getPriceE8();
        if(input==null||fee==null||price==null||input.signum()<=0||fee.signum()<0||fee.compareTo(input)>0||price.signum()<=0)
            return json.createObjectNode().put("verification","INVALID_QUOTED_AMOUNTS");
        BigInteger calculated=input.subtract(fee).multiply(BigInteger.TEN.pow(8)).divide(price);
        return json.createObjectNode().put("verification","QUOTED_ARITHMETIC_ONLY_NOT_EXECUTION")
            .put("inputIncludesFee",true).put("additionalFeeDebit","0")
            .put("quotedTotalDebit",TokenUnits.fromWei(input).toPlainString()).put("includedFee",TokenUnits.fromWei(fee).toPlainString())
            .put("netPricingInput",TokenUnits.fromWei(input.subtract(fee)).toPlainString())
            .put("calculatedOutput",TokenUnits.fromWei(calculated).toPlainString());
    }
}
