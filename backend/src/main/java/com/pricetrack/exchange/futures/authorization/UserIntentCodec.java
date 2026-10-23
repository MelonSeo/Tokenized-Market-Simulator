package com.pricetrack.exchange.futures.authorization;

import com.pricetrack.exchange.futures.chain.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

/** Contract-v1 nested EIP-712. Never signs a user intent or accepts a user private key. */
public final class UserIntentCodec {
    private UserIntentCodec() {}
    public record Approval(String owner,BigInteger nonce,BigInteger deadline,String relayer) {}
    public record Authorization(FuturesChainIdentity chain,String owner,Approval approval,String userSignature,UserIntentV2Codec.Intent intentV2) {
        public Authorization(FuturesChainIdentity chain,String owner,Approval approval,String signature){this(chain,owner,approval,signature,null);}
        @Override public String toString(){return "UserAuthorization[redacted]";}
    }
    public static final String USER_TYPE="FuturesUserIntent(address owner,FuturesReport report,uint256 nonce,uint256 deadline,address relayer)"+FuturesReportCodec.TYPE;
    public static boolean userAction(int n){return n==0||n==1||n==4;}
    public static String address(String s) {
        if(s==null||!s.matches("0x[0-9a-fA-F]{40}")||s.equalsIgnoreCase("0x"+"00".repeat(20)))throw new IllegalArgumentException("INVALID_WALLET_ADDRESS");
        String hex=s.substring(2);
        if(!hex.equals(hex.toLowerCase(Locale.ROOT))&&!hex.equals(hex.toUpperCase(Locale.ROOT))&&!Keys.toChecksumAddress(s).equals(s))throw new IllegalArgumentException("INVALID_WALLET_CHECKSUM");
        return s.toLowerCase(Locale.ROOT);
    }
    public static String account(String owner){return Numeric.toHexString(Hash.sha3(enc(new Bytes32(hash("TMS_FUTURES_WALLET_ACCOUNT_V1")),new Address(address(owner)),new Bytes32(Numeric.hexStringToByteArray(FuturesReport.SYMBOL_HASH)))));}
    private static byte[] hash(String s){return Hash.sha3(s.getBytes(StandardCharsets.UTF_8));}
    private static byte[] enc(Type... fields){return Numeric.hexStringToByteArray(FunctionEncoder.encodeConstructor(List.of(fields)));}
    private static byte[] join(byte[]... fields){var out=new java.io.ByteArrayOutputStream();for(var f:fields)out.writeBytes(f);return out.toByteArray();}
    public static byte[] digest(FuturesChainIdentity c,FuturesReport r,Approval a) {
        if(!c.authorized()||c.v2())throw new IllegalArgumentException("USER_AUTHORIZATION_MODE_REQUIRED");
        byte[] domain=Hash.sha3(enc(new Bytes32(hash("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)")),new Bytes32(hash("TokenizedMarketFuturesUserIntent")),new Bytes32(hash("1")),new Uint256(c.chainId()),new Address(c.engine())));
        byte[] report=Hash.sha3(join(hash(FuturesReportCodec.TYPE),Numeric.hexStringToByteArray(FunctionEncoder.encodeConstructor(FuturesReportCodec.fields(r)))));
        byte[] intent=Hash.sha3(enc(new Bytes32(hash(USER_TYPE)),new Address(address(a.owner())),new Bytes32(report),new Uint256(a.nonce()),new Uint256(a.deadline()),new Address(address(a.relayer()))));
        return Hash.sha3(join(new byte[]{0x19,0x01},domain,intent));
    }
    public static boolean signed(byte[] digest,String signature,String owner) {
        try {
            if(signature==null||!signature.matches("0x[0-9a-fA-F]{130}"))return false;
            byte[] b=Numeric.hexStringToByteArray(signature);BigInteger s=new BigInteger(1,Arrays.copyOfRange(b,32,64));
            if((b[64]!=27&&b[64]!=28)||s.signum()==0||s.compareTo(Sign.CURVE_PARAMS.getN().shiftRight(1))>0)return false;
            return address(owner).equals("0x"+Keys.getAddress(Sign.signedMessageHashToKey(digest,new Sign.SignatureData(b[64],Arrays.copyOfRange(b,0,32),Arrays.copyOfRange(b,32,64)))));
        }catch(Exception invalid){return false;}
    }
    public static void verify(FuturesReport r,String priceSignature,Authorization a) {
        if(a.chain().v2()){UserIntentV2Codec.verifyReport(r,priceSignature,a);return;}
        if(a.intentV2()!=null)throw new IllegalArgumentException("AUTHORIZATION_VERSION_CONFLICT");
        var c=a.chain();String owner=address(a.owner());
        if(!c.authorized()||!r.executor().equalsIgnoreCase(c.engine())||!r.accountRef().equals(account(owner))||!FuturesReportCodec.signedBy(r,c.chainId(),c.rules(),priceSignature,c.priceSigner()))throw new IllegalArgumentException("AUTHORIZED_REPORT_CONFLICT");
        if(userAction(r.action())) {
            var u=a.approval();
            if(u==null||!owner.equals(address(u.owner()))||!c.executor().equals(address(u.relayer()))||u.nonce().signum()<0||u.deadline().signum()<0||u.deadline().compareTo(r.validUntil())>0||!signed(digest(c,r,u),a.userSignature(),owner))throw new IllegalArgumentException("USER_SIGNATURE_INVALID");
        }else if(a.approval()!=null||a.userSignature()!=null)throw new IllegalArgumentException("UNEXPECTED_USER_AUTHORIZATION");
    }
    public static String encode(FuturesReport r,String priceSignature,Authorization a) {
        if(a.chain().v2())return UserIntentV2Codec.encode(r,priceSignature,a);
        verify(r,priceSignature,a);
        if(!userAction(r.action()))return FuturesReportCodec.encode(r,priceSignature);
        var u=a.approval();String name=r.action()==0?"open":r.action()==1?"topUp":"close";
        return FunctionEncoder.encode(new Function(name,List.of(new StaticStruct(FuturesReportCodec.fields(r)),new DynamicBytes(Numeric.hexStringToByteArray(priceSignature)),
            new StaticStruct(new Address(u.owner()),new Uint256(u.nonce()),new Uint256(u.deadline()),new Address(u.relayer())),new DynamicBytes(Numeric.hexStringToByteArray(a.userSignature()))),List.of()));
    }
    /** Big integers remain decimal strings for wallets; no JS Number conversion. */
    public static Map<String,Object> typedData(FuturesChainIdentity c,FuturesReport r,Approval a) {
        if(!c.authorized()||c.v2())throw new IllegalArgumentException("AUTHORIZATION_VERSION_CONFLICT");
        String[] names={"operationId","positionId","accountRef","symbolHash","action","expectedVersion","expectedCallEpoch","side","leverage","quantity","entryPriceE8","collateralBefore","collateralAfter","priceE8","observedAt","validUntil","policyHash","executor"};
        var fields=FuturesReportCodec.fields(r);var report=new LinkedHashMap<String,Object>();var types=new ArrayList<Map<String,String>>();
        for(int i=0;i<names.length;i++){Type f=fields.get(i);Object v=f.getValue();report.put(names[i],v instanceof byte[] b?Numeric.toHexString(b):v.toString());types.add(Map.of("name",names[i],"type",f.getTypeAsString()));}
        return Map.of("domain",Map.of("name","TokenizedMarketFuturesUserIntent","version","1","chainId",c.chainId().toString(),"verifyingContract",c.engine()),"primaryType","FuturesUserIntent",
            "types",Map.of("EIP712Domain",List.of(Map.of("name","name","type","string"),Map.of("name","version","type","string"),Map.of("name","chainId","type","uint256"),Map.of("name","verifyingContract","type","address")),"FuturesReport",types,"FuturesUserIntent",List.of(Map.of("name","owner","type","address"),Map.of("name","report","type","FuturesReport"),Map.of("name","nonce","type","uint256"),Map.of("name","deadline","type","uint256"),Map.of("name","relayer","type","address"))),
            "message",Map.of("owner",a.owner(),"report",report,"nonce",a.nonce().toString(),"deadline",a.deadline().toString(),"relayer",a.relayer()));
    }
}
