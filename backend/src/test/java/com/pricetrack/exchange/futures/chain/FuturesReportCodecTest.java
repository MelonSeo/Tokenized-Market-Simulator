package com.pricetrack.exchange.futures.chain;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;

class FuturesReportCodecTest {
    static final String ENGINE="0x"+"12".repeat(20), EXECUTOR="0x"+"34".repeat(20);
    FuturesReport report(int action) {
        return new FuturesReport(Hash.sha3String("op"),Hash.sha3String("pos"),Hash.sha3String("account"),Hash.sha3String("mSEC"),
            action,BigInteger.ONE,BigInteger.ZERO,0,10,new BigInteger("13333333333333333333"),new BigInteger("7500000000000"),
            BigInteger.TEN.pow(23),BigInteger.TEN.pow(23),new BigInteger("7000000000000"),BigInteger.valueOf(1000),BigInteger.valueOf(1030),Hash.sha3String("policy"),EXECUTOR);
    }
    @Test void matchesIndependentStructuredDataEncoder() throws Exception {
        var r=report(5); var fields=List.of("operationId:bytes32","positionId:bytes32","accountRef:bytes32","symbolHash:bytes32","action:uint8","expectedVersion:uint256","expectedCallEpoch:uint256","side:uint8","leverage:uint8","quantity:uint256","entryPriceE8:uint256","collateralBefore:uint256","collateralAfter:uint256","priceE8:uint256","observedAt:uint256","validUntil:uint256","policyHash:bytes32","executor:address");
        var types=new LinkedHashMap<String,Object>();
        types.put("EIP712Domain",List.of(Map.of("name","name","type","string"),Map.of("name","version","type","string"),Map.of("name","chainId","type","uint256"),Map.of("name","verifyingContract","type","address")));
        types.put("FuturesReport",fields.stream().map(f->Map.of("name",f.split(":")[0],"type",f.split(":")[1])).toList());
        var message=new LinkedHashMap<String,Object>();
        var values=FuturesReportCodec.fields(r);
        for(int i=0;i<fields.size();i++) { Object value=values.get(i).getValue(); message.put(fields.get(i).split(":")[0],value instanceof byte[] b?Numeric.toHexString(b):value.toString()); }
        var input=Map.of("types",types,"primaryType","FuturesReport","domain",Map.of("name","TokenizedMarketFutures","version","1","chainId","31337","verifyingContract",ENGINE),"message",message);
        var encoder=new StructuredDataEncoder(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(input));
        assertThat(FuturesReportCodec.digest(r,BigInteger.valueOf(31337),ENGINE)).isEqualTo(encoder.hashStructuredData());
    }
    @Test void domainActionAndSignerChangesInvalidateSignature() {
        var r=report(5); var key=ECKeyPair.create(BigInteger.valueOf(12345));
        String signer="0x"+Keys.getAddress(key),sig=FuturesReportCodec.sign(r,BigInteger.valueOf(31337),ENGINE,key);
        assertThat(FuturesReportCodec.signedBy(r,BigInteger.valueOf(31337),ENGINE,sig,signer)).isTrue();
        assertThat(FuturesReportCodec.signedBy(r,BigInteger.ONE,ENGINE,sig,signer)).isFalse();
        assertThat(FuturesReportCodec.signedBy(r,BigInteger.valueOf(31337),EXECUTOR,sig,signer)).isFalse();
        assertThat(FuturesReportCodec.signedBy(report(4),BigInteger.valueOf(31337),ENGINE,sig,signer)).isFalse();
        assertThat(FuturesReportCodec.signedBy(r,BigInteger.valueOf(31337),ENGINE,sig,EXECUTOR)).isFalse();
        assertThat(FuturesReportCodec.signedBy(r,BigInteger.valueOf(31337),ENGINE,"0x1234",signer)).isFalse();
    }
    @Test void sixFunctionsHaveDistinctSelectorsAndStaticTupleOffset() {
        var selectors=new HashSet<String>();
        for(int a=0;a<6;a++) {
            String encoded=FuturesReportCodec.encode(report(a),"0x"+"11".repeat(65)); selectors.add(encoded.substring(0,10));
            // 18 static tuple words + dynamic signature offset word: 19 * 32 bytes.
            assertThat(Numeric.toBigInt(encoded.substring(10+18*64,10+19*64))).isEqualTo(BigInteger.valueOf(19*32));
        }
        assertThat(selectors).hasSize(6);
    }
}
