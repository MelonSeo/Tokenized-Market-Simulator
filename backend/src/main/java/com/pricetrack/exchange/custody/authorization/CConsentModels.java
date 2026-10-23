package com.pricetrack.exchange.custody.authorization;

import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.proof.CEip712Verifier;
import com.pricetrack.exchange.custody.runtime.CCustodyIdentity;
import com.pricetrack.exchange.custody.runtime.CCustodyFailure;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

/** Immutable consent metadata only. No current nonce, RPC, asset or execution authorization. */
public final class CConsentModels {
    private CConsentModels() {}
    public static final String SCOPE="USER_SIGNATURE_RECORDED_NOT_PREPARED_OR_EXECUTABLE";
    public record Draft(String deploymentId,long userId,String keyHash,String requestHash,
                        Intent intent,String intentDigest,Instant createdAt) {
        @Override public String toString(){return "CConsentDraft[operationId="+intent.operationId()+"]";}
    }
    /** The original signature is stored privately, never returned by this public result. */
    public record Consent(String operationId,String intentDigest,Instant recordedAt) {
        @com.fasterxml.jackson.annotation.JsonProperty("scope")
        public String scope(){return SCOPE;}
    }
    public static String keyHash(String key){
        if(key==null||key.length()<8||key.length()>128||!key.matches("[A-Za-z0-9._:-]+"))throw fail("INVALID_KEY",400);
        return sha("TMS_C_CONSENT_KEY_V1|"+key);
    }
    public static String requestHash(CCustodyIdentity identity,long uid,String digest){
        return sha("TMS_C_CONSENT_REQUEST_V1|"+identity.deploymentId()+"|"+uid+"|"+digest);
    }
    public static String digest(CCustodyIdentity identity,Intent i){return new CEip712Verifier().intentDigest(i,identity.chainId(),identity.controller().address());}
    public static String accountRef(String owner,String symbol){
        byte[] bytes=new byte[96];
        System.arraycopy(Hash.sha3("TMS_C_FUTURES_WALLET_ACCOUNT_V1".getBytes(StandardCharsets.UTF_8)),0,bytes,0,32);
        System.arraycopy(Numeric.hexStringToByteArray(CCustodyIdentity.address(owner)),0,bytes,44,20);
        System.arraycopy(Numeric.hexStringToByteArray(CCustodyIdentity.hash(symbol)),0,bytes,64,32);
        return Numeric.toHexString(Hash.sha3(bytes));
    }
    public static void validate(CCustodyIdentity c,Intent i,Instant now){
        if(i==null||now==null)throw fail("INVALID_INTENT",400);
        if(!i.relayer().equals(c.relayer())||!i.symbolHash().equals(c.symbolHash())||!i.policyHash().equals(c.policyHash())
                ||!i.accountRef().equals(accountRef(i.owner(),c.symbolHash()))
                ||i.operationId().equals("0x"+"0".repeat(64))||i.positionId().equals("0x"+"0".repeat(64))
                ||!java.util.Set.of(BigInteger.ZERO,BigInteger.ONE,BigInteger.valueOf(4)).contains(i.action()))throw fail("INTENT_SCOPE",400);
        BigInteger seconds=BigInteger.valueOf(now.getEpochSecond());
        if(i.issuedAt().compareTo(seconds)>0||i.deadline().compareTo(i.issuedAt())<=0
                ||i.deadline().subtract(i.issuedAt()).compareTo(BigInteger.valueOf(120))>0
                ||seconds.compareTo(i.deadline())>0)throw fail("INTENT_WINDOW",409);
        bounded(i.priceMinE8(),i.priceMaxE8(),FuturesMath.PRICE_LIMIT);
        bounded(i.quantityMin(),i.quantityMax(),FuturesMath.AMOUNT_LIMIT);
        // Only static syntax/bounds. Position ownership/version, financial formulas and current nonce are NOT verified here.
    }
    private static void bounded(BigInteger min,BigInteger max,BigInteger limit){
        if(min.signum()<=0||min.compareTo(max)>0||max.compareTo(limit)>=0)throw fail("INTENT_BOUNDS",400);
    }
    static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException("C_CONSENT_HASH");}}
    static CCustodyFailure fail(String code,int status){return new CCustodyFailure("C_CONSENT_"+code,status);}
}
