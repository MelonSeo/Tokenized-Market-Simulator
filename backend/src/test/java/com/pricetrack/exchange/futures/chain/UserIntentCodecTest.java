package com.pricetrack.exchange.futures.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import static org.assertj.core.api.Assertions.*;

class UserIntentCodecTest {
    static final Credentials USER=Credentials.create("6f"),PRICE=Credentials.create("14d"); // public fixtures 111/333
    static final FuturesChainIdentity C=new FuturesChainIdentity(BigInteger.valueOf(31337),"0x5615deb798bb3e4dfa0139dfa1b3d433cc23b72f","0x"+"0".repeat(36)+"beef",PRICE.getAddress(),"0x"+"13".repeat(20));
    static String sign(byte[] d,Credentials c){var s=Sign.signMessage(d,c.getEcKeyPair(),false);byte[] bytes=new byte[65];System.arraycopy(s.getR(),0,bytes,0,32);System.arraycopy(s.getS(),0,bytes,32,32);bytes[64]=s.getV()[0];return Numeric.toHexString(bytes);}
    static FuturesReport vector(){return new FuturesReport("0x"+"0".repeat(63)+"1",Hash.sha3String("position"),UserIntentCodec.account(USER.getAddress()),FuturesReport.SYMBOL_HASH,0,BigInteger.ZERO,BigInteger.ZERO,0,10,new BigInteger("13333333333333333333"),new BigInteger("7500000000000"),BigInteger.ZERO,BigInteger.TEN.pow(23),new BigInteger("7500000000000"),BigInteger.valueOf(1000),BigInteger.valueOf(1030),FuturesLedger.POLICY_HASH,C.engine());}
    @Test void matchesExecutedSolidityGoldenVectorAndWeb3jTypedData()throws Exception {
        var r=vector();var a=new Approval(USER.getAddress(),BigInteger.ZERO,BigInteger.valueOf(1030),C.executor());
        assertThat(UserIntentCodec.account(USER.getAddress())).isEqualTo("0x4b6b177e1614d11efdf3235350fb39cfa91a2f4347750616cace0cd0666aaccd");
        byte[] expected=Numeric.hexStringToByteArray("0x974acbd70c0a12cc8a5438f719612e4dffb7012e13db0dd731d5bcc8855b4528");
        // Solidity testIndependentNestedTypedDataDigest trace, executed unchanged in this stage.
        assertThat(UserIntentCodec.digest(C,r,a)).isEqualTo(expected);
        String json=new ObjectMapper().writeValueAsString(UserIntentCodec.typedData(C,r,a));
        assertThat(new StructuredDataEncoder(json).hashStructuredData()).isEqualTo(expected);
        assertThat(json).contains("13333333333333333333").doesNotContain("userSignature","privateKey");
    }
    @Test void realSignaturesDomainSeparationAndCalldataSelectors(){
        var r=vector();var a=new Approval(USER.getAddress(),BigInteger.ZERO,r.validUntil(),C.executor());
        var auth=new Authorization(C,USER.getAddress(),a,sign(UserIntentCodec.digest(C,r,a),USER));
        String ps=FuturesReportCodec.sign(r,C.chainId(),C.rules(),PRICE.getEcKeyPair());
        UserIntentCodec.verify(r,ps,auth);
        assertThat(UserIntentCodec.encode(r,ps,auth)).startsWith(Hash.sha3String("open((bytes32,bytes32,bytes32,bytes32,uint8,uint256,uint256,uint8,uint8,uint256,uint256,uint256,uint256,uint256,uint256,uint256,bytes32,address),bytes,(address,uint256,uint256,address),bytes)").substring(0,10));
        var wrong=new Authorization(new FuturesChainIdentity(C.chainId().add(BigInteger.ONE),C.engine(),C.executor(),C.priceSigner(),C.rules()),USER.getAddress(),a,auth.userSignature());
        assertThatThrownBy(()->UserIntentCodec.verify(r,ps,wrong)).hasMessage("AUTHORIZED_REPORT_CONFLICT");
        assertThatThrownBy(()->UserIntentCodec.verify(r,ps,new Authorization(C,USER.getAddress(),a,sign(UserIntentCodec.digest(C,r,a),PRICE)))).hasMessage("USER_SIGNATURE_INVALID");
        assertThat(auth.toString()).doesNotContain(auth.userSignature());
    }
}
