package com.pricetrack.exchange.futures.chain;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.authorization.*;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.Authorization;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;

class UserIntentV2CodecTest {
    static final FuturesChainIdentity C=new FuturesChainIdentity(UserIntentCodecTest.C.chainId(),UserIntentCodecTest.C.engine(),UserIntentCodecTest.C.executor(),UserIntentCodecTest.C.priceSigner(),UserIntentCodecTest.C.rules(),"2");
    static UserIntentV2Codec.Intent vector(){return new UserIntentV2Codec.Intent(UserIntentCodecTest.USER.getAddress(),C.executor(),"0x"+"0".repeat(63)+"1",Hash.sha3String("v2 position"),UserIntentCodec.account(UserIntentCodecTest.USER.getAddress()),FuturesReport.SYMBOL_HASH,0,BigInteger.ZERO,BigInteger.ZERO,0,10,BigInteger.ZERO,BigInteger.ZERO,BigInteger.TEN.pow(23),new BigInteger("7400000000000"),new BigInteger("7600000000000"),new BigInteger("13157894736842105263"),new BigInteger("13513513513513513513"),BigInteger.ZERO,BigInteger.valueOf(1000),BigInteger.valueOf(1120),FuturesLedger.POLICY_HASH);}
    @Test void matchesActualSolidityTraceAndIndependentTypedDataEncoder()throws Exception{
        var i=vector();byte[] expected=Numeric.hexStringToByteArray("0x28e15d377181266709dd6ad20214513b715945396ff4a99a656a4341bbbf26a2");
        assertThat(UserIntentV2Codec.digest(C,i)).isEqualTo(expected);
        String json=new ObjectMapper().writeValueAsString(UserIntentV2Codec.typedData(C,i));assertThat(new StructuredDataEncoder(json).hashStructuredData()).isEqualTo(expected);
        assertThat(json).contains("FuturesUserIntentV2","13157894736842105263").doesNotContain("FuturesReport","userSignature","priceSignature");
    }
    @Test void v1V2DomainsAbiAndSerializedLegacyIdentityRemainDistinct()throws Exception{
        var json=new ObjectMapper();var legacy=json.readValue("{\"chainId\":31337,\"engine\":\""+C.engine()+"\",\"executor\":\""+C.executor()+"\",\"priceSigner\":\""+C.priceSigner()+"\",\"rules\":\""+C.rules()+"\"}",FuturesChainIdentity.class);assertThat(legacy.authorizationVersion()).isEqualTo("1");
        assertThatThrownBy(()->UserIntentV2Codec.digest(legacy,vector())).hasMessage("AUTHORIZATION_VERSION_CONFLICT");
        var i=vector();var r0=UserIntentCodecTest.vector();var r=new FuturesReport(i.operationId(),i.positionId(),i.accountRef(),i.symbolHash(),0,i.expectedVersion(),i.expectedCallEpoch(),0,10,r0.quantity(),r0.entryPriceE8(),i.collateralBefore(),i.collateralAfter(),r0.priceE8(),BigInteger.valueOf(1010),BigInteger.valueOf(1040),i.policyHash(),C.engine());
        String us=UserIntentCodecTest.sign(UserIntentV2Codec.digest(C,i),UserIntentCodecTest.USER),ps=FuturesReportCodec.sign(r,C.chainId(),C.rules(),UserIntentCodecTest.PRICE.getEcKeyPair());
        var a=new Authorization(C,i.owner(),null,us,i);String encoded=UserIntentCodec.encode(r,ps,a);
        assertThat(encoded).startsWith(Hash.sha3String("open((bytes32,bytes32,bytes32,bytes32,uint8,uint256,uint256,uint8,uint8,uint256,uint256,uint256,uint256,uint256,uint256,uint256,bytes32,address),bytes,(address,address,bytes32,bytes32,bytes32,bytes32,uint8,uint256,uint256,uint8,uint8,uint256,uint256,uint256,uint256,uint256,uint256,uint256,uint256,uint256,uint256,bytes32),bytes)").substring(0,10));
        assertThatThrownBy(()->UserIntentCodec.verify(r,ps,new Authorization(legacy,i.owner(),null,us,i))).hasMessage("AUTHORIZATION_VERSION_CONFLICT");
        assertThatThrownBy(()->UserIntentV2Codec.verifyConsent(new FuturesChainIdentity(C.chainId().add(BigInteger.ONE),C.engine(),C.executor(),C.priceSigner(),C.rules(),"2"),i,us)).hasMessage("USER_INTENT_V2_INVALID");
        assertThat(a.toString()).doesNotContain(us);
    }
}
