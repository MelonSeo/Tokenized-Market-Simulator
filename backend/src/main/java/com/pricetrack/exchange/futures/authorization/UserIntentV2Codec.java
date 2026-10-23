package com.pricetrack.exchange.futures.authorization;

import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.authorization.UserIntentCodec.Authorization;
import java.math.BigInteger;
import java.util.*;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

/** Exact contract V2 consent; never creates a user's signature. All amounts are integers/strings. */
public final class UserIntentV2Codec {
    private UserIntentV2Codec(){}
    public record Intent(String owner,String relayer,String operationId,String positionId,String accountRef,String symbolHash,int action,
        BigInteger expectedVersion,BigInteger expectedCallEpoch,int side,int leverage,BigInteger entryPriceE8,BigInteger collateralBefore,BigInteger collateralAfter,
        BigInteger priceMinE8,BigInteger priceMaxE8,BigInteger quantityMin,BigInteger quantityMax,BigInteger nonce,BigInteger issuedAt,BigInteger deadline,String policyHash){}
    public static final String TYPE="FuturesUserIntentV2(address owner,address relayer,bytes32 operationId,bytes32 positionId,bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 entryPriceE8,uint256 collateralBefore,uint256 collateralAfter,uint256 priceMinE8,uint256 priceMaxE8,uint256 quantityMin,uint256 quantityMax,uint256 nonce,uint256 issuedAt,uint256 deadline,bytes32 policyHash)";
    private static final String[] NAMES={"owner","relayer","operationId","positionId","accountRef","symbolHash","action","expectedVersion","expectedCallEpoch","side","leverage","entryPriceE8","collateralBefore","collateralAfter","priceMinE8","priceMaxE8","quantityMin","quantityMax","nonce","issuedAt","deadline","policyHash"};
    private static Bytes32 b(String s){return new Bytes32(Numeric.hexStringToByteArray(s));}
    private static Uint256 n(BigInteger n){if(n==null||n.signum()<0||n.bitLength()>256)throw new IllegalArgumentException("INVALID_INTENT_INTEGER");return new Uint256(n);}
    public static List<Type> fields(Intent i){return List.of(new Address(UserIntentCodec.address(i.owner())),new Address(UserIntentCodec.address(i.relayer())),b(i.operationId()),b(i.positionId()),b(i.accountRef()),b(i.symbolHash()),new Uint8(i.action()),n(i.expectedVersion()),n(i.expectedCallEpoch()),new Uint8(i.side()),new Uint8(i.leverage()),n(i.entryPriceE8()),n(i.collateralBefore()),n(i.collateralAfter()),n(i.priceMinE8()),n(i.priceMaxE8()),n(i.quantityMin()),n(i.quantityMax()),n(i.nonce()),n(i.issuedAt()),n(i.deadline()),b(i.policyHash()));}
    private static byte[] enc(List<Type> t){return Numeric.hexStringToByteArray(FunctionEncoder.encodeConstructor(t));}
    public static byte[] digest(FuturesChainIdentity c,Intent i){
        if(!c.v2())throw new IllegalArgumentException("AUTHORIZATION_VERSION_CONFLICT");
        byte[] domain=Hash.sha3(enc(List.of(b(Hash.sha3String("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)")),b(Hash.sha3String("TokenizedMarketFuturesUserIntent")),b(Hash.sha3String("2")),n(c.chainId()),new Address(c.engine()))));
        var f=new ArrayList<Type>();f.add(b(Hash.sha3String(TYPE)));f.addAll(fields(i));byte[] h=Hash.sha3(enc(f));
        byte[] joined=new byte[66];joined[0]=0x19;joined[1]=0x01;System.arraycopy(domain,0,joined,2,32);System.arraycopy(h,0,joined,34,32);return Hash.sha3(joined);
    }
    public static void validate(FuturesChainIdentity c,Intent i){
        fields(i);
        if(!c.v2()||!UserIntentCodec.address(i.relayer()).equals(c.executor())||!i.accountRef().equals(UserIntentCodec.account(i.owner()))
            ||!i.symbolHash().equals(FuturesReport.SYMBOL_HASH)||!i.policyHash().equals(FuturesLedger.POLICY_HASH)||!UserIntentCodec.userAction(i.action())
            ||i.deadline().compareTo(i.issuedAt())<=0||i.deadline().subtract(i.issuedAt()).compareTo(BigInteger.valueOf(120))>0)bad();
        if(i.priceMinE8().signum()<=0||i.priceMinE8().compareTo(i.priceMaxE8())>0||i.priceMaxE8().compareTo(BigInteger.TEN.pow(20))>=0
            ||i.quantityMin().signum()<=0||i.quantityMin().compareTo(i.quantityMax())>0||i.quantityMax().compareTo(BigInteger.TEN.pow(30))>=0)bad();
        if(i.action()==0?i.entryPriceE8().signum()!=0:!i.quantityMin().equals(i.quantityMax()))bad();
    }
    public static void verifyConsent(FuturesChainIdentity c,Intent i,String sig){validate(c,i);if(!UserIntentCodec.signed(digest(c,i),sig,i.owner()))bad();}
    public static void time(Intent i,long now){if(i.issuedAt().compareTo(BigInteger.valueOf(now))>0||i.deadline().compareTo(BigInteger.valueOf(now))<0)throw new FuturesLedger.Failure("USER_INTENT_EXPIRED_OR_FUTURE");}
    public static void verifyReport(FuturesReport r,String ps,Authorization a){
        var c=a.chain();String owner=UserIntentCodec.address(a.owner());
        if(!c.v2()||a.approval()!=null||!r.executor().equals(c.engine())||!r.accountRef().equals(UserIntentCodec.account(owner))||!FuturesReportCodec.signedBy(r,c.chainId(),c.rules(),ps,c.priceSigner()))bad();
        if(!UserIntentCodec.userAction(r.action())){if(a.intentV2()!=null||a.userSignature()!=null)bad();return;}
        var i=a.intentV2();if(i==null||!owner.equals(UserIntentCodec.address(i.owner())))bad();verifyConsent(c,i,a.userSignature());
        if(!i.operationId().equals(r.operationId())||!i.positionId().equals(r.positionId())||!i.accountRef().equals(r.accountRef())||!i.symbolHash().equals(r.symbolHash())||i.action()!=r.action()
            ||!i.expectedVersion().equals(r.expectedVersion())||!i.expectedCallEpoch().equals(r.expectedCallEpoch())||i.side()!=r.side()||i.leverage()!=r.leverage()
            ||!i.collateralBefore().equals(r.collateralBefore())||!i.collateralAfter().equals(r.collateralAfter())||!i.policyHash().equals(r.policyHash()))bad();
        if(r.priceE8().compareTo(i.priceMinE8())<0||r.priceE8().compareTo(i.priceMaxE8())>0||r.quantity().compareTo(i.quantityMin())<0||r.quantity().compareTo(i.quantityMax())>0)throw new FuturesLedger.Failure("EXECUTION_OUTSIDE_USER_BOUNDS");
        if(r.action()==0){if(!r.entryPriceE8().equals(r.priceE8())||!r.quantity().equals(FuturesMath.quantity(i.collateralAfter(),i.leverage(),r.priceE8())))bad();}
        else if(!i.entryPriceE8().equals(r.entryPriceE8()))bad();
    }
    public static String encode(FuturesReport r,String ps,Authorization a){verifyReport(r,ps,a);if(!UserIntentCodec.userAction(r.action()))return FuturesReportCodec.encode(r,ps);
        return FunctionEncoder.encode(new Function(r.action()==0?"open":r.action()==1?"topUp":"close",List.of(new StaticStruct(FuturesReportCodec.fields(r)),new DynamicBytes(Numeric.hexStringToByteArray(ps)),new StaticStruct(fields(a.intentV2())),new DynamicBytes(Numeric.hexStringToByteArray(a.userSignature()))),List.of()));}
    public static Map<String,Object> typedData(FuturesChainIdentity c,Intent i){validate(c,i);var values=new LinkedHashMap<String,Object>();var types=new ArrayList<Map<String,String>>();var fields=fields(i);
        for(int j=0;j<NAMES.length;j++){var f=fields.get(j);values.put(NAMES[j],f.getValue() instanceof byte[] b?Numeric.toHexString(b):f.getValue().toString());types.add(Map.of("name",NAMES[j],"type",f.getTypeAsString()));}
        return Map.of("domain",Map.of("name","TokenizedMarketFuturesUserIntent","version","2","chainId",c.chainId().toString(),"verifyingContract",c.engine()),"primaryType","FuturesUserIntentV2","types",Map.of("EIP712Domain",List.of(Map.of("name","name","type","string"),Map.of("name","version","type","string"),Map.of("name","chainId","type","uint256"),Map.of("name","verifyingContract","type","address")),"FuturesUserIntentV2",types),"message",values);
    }
    private static void bad(){throw new IllegalArgumentException("USER_INTENT_V2_INVALID");}
}
