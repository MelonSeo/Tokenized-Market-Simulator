package com.pricetrack.exchange.custody.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.runtime.*;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import static org.junit.jupiter.api.Assertions.*;

public class CConsentModelsTest {
    static final Instant NOW=Instant.parse("2026-10-23T12:00:00Z");
    static final ECKeyPair KEY=ECKeyPair.create(BigInteger.valueOf(12345));
    static final String OWNER="0x"+Keys.getAddress(KEY);
    static String addr(int n){return "0x"+String.format("%040x",n);}
    static String hash(int n){return "0x"+String.format("%064x",n);}
    static CCustodyIdentity identity(){return new CCustodyIdentity(BigInteger.valueOf(31337),hash(9),
        new CCustodyIdentity.Contract(addr(1),hash(1)),new CCustodyIdentity.Contract(addr(2),hash(2)),
        new CCustodyIdentity.Contract(addr(3),hash(3)),new CCustodyIdentity.Contract(addr(4),hash(4)),addr(5),addr(6),addr(7),hash(10),hash(11),"http://localhost:5173");}
    static Intent intent(){var c=identity();return new Intent(OWNER,c.relayer(),hash(20),hash(21),CConsentModels.accountRef(OWNER,c.symbolHash()),c.symbolHash(),
        BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.TEN,BigInteger.ZERO,BigInteger.ZERO,BigInteger.valueOf(1000),
        BigInteger.valueOf(90),BigInteger.valueOf(110),BigInteger.ONE,BigInteger.valueOf(200),BigInteger.ZERO,
        BigInteger.valueOf(NOW.getEpochSecond()),BigInteger.valueOf(NOW.plusSeconds(120).getEpochSecond()),c.policyHash());}
    static Intent change(Intent i,String name,Object value){var j=new ObjectMapper();Map<String,Object> map=j.convertValue(i,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});map.put(name,value);return j.convertValue(map,Intent.class);}
    static String signature(Intent i,ECKeyPair key){return signatureDigest(CConsentModels.digest(identity(),i),key);}
    static String signatureDigest(String digest,ECKeyPair key){var s=Sign.signMessage(Numeric.hexStringToByteArray(digest),key,false);return Numeric.toHexString(s.getR())+Numeric.cleanHexPrefix(Numeric.toHexString(s.getS()))+Numeric.cleanHexPrefix(Numeric.toHexString(s.getV()));}
    /** Public trial key only; a second valid low-s signature for exact-signature replay checks. */
    static String alternateSignature(Intent i){BigInteger n=new BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",16),k=BigInteger.valueOf(999);
        BigInteger point=ECKeyPair.create(k).getPublicKey(),r=point.shiftRight(256).mod(n),z=new BigInteger(CConsentModels.digest(identity(),i).substring(2),16);
        BigInteger s=k.modInverse(n).multiply(z.add(r.multiply(KEY.getPrivateKey()))).mod(n);int v=27+(point.testBit(0)?1:0);
        if(s.compareTo(n.shiftRight(1))>0){s=n.subtract(s);v=27+((v-27)^1);}return "0x"+String.format("%064x%064x%02x",r,s,v);}
    @Test void scopeAndFingerprintsAreDeterministicAndSeparated()throws Exception{var c=identity();var i=intent();String d=CConsentModels.digest(c,i);assertEquals(d,CConsentModels.digest(c,i));assertNotEquals(CConsentModels.keyHash("request-123"),CConsentModels.requestHash(c,1,d));assertNotEquals(CConsentModels.requestHash(c,1,d),CConsentModels.requestHash(c,2,d));assertEquals("USER_SIGNATURE_RECORDED_NOT_PREPARED_OR_EXECUTABLE",CConsentModels.SCOPE);assertFalse(new CConsentModels.Draft(c.deploymentId(),1,"key","request",i,d,NOW).toString().contains(OWNER));var j=new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);var tree=j.readTree(j.writeValueAsString(new CConsentModels.Consent(i.operationId(),d,NOW)));assertEquals(CConsentModels.SCOPE,tree.get("scope").asText());assertEquals(NOW.toString(),tree.get("recordedAt").asText());assertFalse(tree.has("signature"));assertFalse(tree.has("userSignature"));}
    @Test void exactInclusiveWindowAndFutureIssuanceAreSeparate(){var c=identity();var i=intent();CConsentModels.validate(c,i,NOW.plusSeconds(120));assertThrows(CCustodyFailure.class,()->CConsentModels.validate(c,i,NOW.minusSeconds(1)));assertThrows(CCustodyFailure.class,()->CConsentModels.validate(c,i,NOW.plusSeconds(121)));assertThrows(CCustodyFailure.class,()->CConsentModels.validate(c,change(i,"deadline",i.deadline().add(BigInteger.ONE)),NOW));}
    @Test void invalidScopeAndFiniteBoundsFailClosed(){var c=identity();var i=intent();for(String name:List.of("relayer","symbolHash","policyHash","accountRef","operationId","positionId","action")){Object value=name.equals("relayer")?addr(99):name.equals("action")?BigInteger.valueOf(5):hash(0);assertThrows(CCustodyFailure.class,()->CConsentModels.validate(c,change(i,name,value),NOW));}for(String name:List.of("priceMinE8","quantityMin"))assertThrows(CCustodyFailure.class,()->CConsentModels.validate(c,change(i,name,BigInteger.ZERO),NOW));assertThrows(CCustodyFailure.class,()->CConsentModels.validate(c,change(i,"priceMaxE8",BigInteger.TEN.pow(20)),NOW));assertThrows(CCustodyFailure.class,()->CConsentModels.validate(c,change(i,"quantityMax",BigInteger.TEN.pow(30)),NOW));}
    @Test void keyScopeIsBoundedAsciiAndNoNonceFreshnessIsClaimed(){for(String key:List.of("short","한글-12345678","x".repeat(129),"with space"))assertThrows(CCustodyFailure.class,()->CConsentModels.keyHash(key));CConsentModels.validate(identity(),change(intent(),"nonce",BigInteger.ONE.shiftLeft(200)),NOW);assertEquals(64,CConsentModels.keyHash("request-123").length());}
}
