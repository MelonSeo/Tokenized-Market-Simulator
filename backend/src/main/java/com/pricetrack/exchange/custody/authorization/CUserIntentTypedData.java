package com.pricetrack.exchange.custody.authorization;

import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure wallet JSON syntax only. Does not authorize execution, verify current nonce,
 * deadlines, prices, JWT, ownership, available assets or canonical chain state. */
public final class CUserIntentTypedData {
    private CUserIntentTypedData() {}
    private static final String[] NAMES={"owner","relayer","operationId","positionId","accountRef","symbolHash",
        "action","expectedVersion","expectedCallEpoch","side","leverage","entryPriceE8","collateralBefore",
        "collateralAfter","priceMinE8","priceMaxE8","quantityMin","quantityMax","nonce","issuedAt","deadline","policyHash"};
    private static final List<Map<String,String>> INTENT_FIELDS=java.util.stream.IntStream.range(0,NAMES.length)
        .mapToObj(n->field(NAMES[n],n<2?"address":n<6||n==21?"bytes32":n==6||n==9||n==10?"uint8":"uint256")).toList();
    private static final List<Map<String,String>> DOMAIN_FIELDS=List.of(field("name","string"),field("version","string"),
        field("chainId","uint256"),field("verifyingContract","address"));
    private static Map<String,String> field(String name,String type) {return Map.of("name",name,"type",type);}
    private static <V> Map<String,V> frozen(Map<String,V> values) {return Collections.unmodifiableMap(new LinkedHashMap<>(values));}

    public static Map<String,Object> format(Intent intent,BigInteger chainId,String controller) {
        if(intent==null)throw new IllegalArgumentException("C_TYPED_DATA_INTENT");
        // Reuse C domain syntax checks, not any B domain or financial validator.
        new CEip712Verifier().intentDomain(chainId,controller);
        var domain=new LinkedHashMap<String,String>();
        domain.put("name","TokenizedMarketCFuturesUserIntent");domain.put("version","1");
        domain.put("chainId",chainId.toString());domain.put("verifyingContract",CAbiModels.address(controller));
        Object[] values={intent.owner(),intent.relayer(),intent.operationId(),intent.positionId(),intent.accountRef(),intent.symbolHash(),
            intent.action(),intent.expectedVersion(),intent.expectedCallEpoch(),intent.side(),intent.leverage(),intent.entryPriceE8(),
            intent.collateralBefore(),intent.collateralAfter(),intent.priceMinE8(),intent.priceMaxE8(),intent.quantityMin(),
            intent.quantityMax(),intent.nonce(),intent.issuedAt(),intent.deadline(),intent.policyHash()};
        var message=new LinkedHashMap<String,String>();
        for(int n=0;n<NAMES.length;n++)message.put(NAMES[n],values[n].toString());
        var types=new LinkedHashMap<String,Object>();types.put("EIP712Domain",DOMAIN_FIELDS);types.put("CFuturesIntent",INTENT_FIELDS);
        var result=new LinkedHashMap<String,Object>();result.put("domain",frozen(domain));result.put("types",frozen(types));
        result.put("primaryType","CFuturesIntent");result.put("message",frozen(message));return frozen(result);
    }
}
