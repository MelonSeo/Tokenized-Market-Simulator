package com.pricetrack.exchange.custody.proof;

import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.abi.CAbiModels.Report;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

/** Exact C typed digests and EOA signature recovery only. No RPC, JWT, sender/nonce/deadline,
 * execution bounds, Receipt, financial authorization or canonicality verification. */
public final class CEip712Verifier {
    private static final HexFormat HEX=HexFormat.of();
    private static final String ZERO_ADDRESS="0x"+"0".repeat(40);
    private static final BigInteger N=new BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",16);
    private static final BigInteger HALF_N=N.shiftRight(1);
    private static final byte[] DOMAIN_TYPE=keccakText("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)");
    private static final byte[] INTENT_TYPE=keccakText("CFuturesIntent(address owner,address relayer,bytes32 operationId,bytes32 positionId,bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 entryPriceE8,uint256 collateralBefore,uint256 collateralAfter,uint256 priceMinE8,uint256 priceMaxE8,uint256 quantityMin,uint256 quantityMax,uint256 nonce,uint256 issuedAt,uint256 deadline,bytes32 policyHash)");
    private static final byte[] REPORT_TYPE=keccakText("FuturesReport(bytes32 operationId,bytes32 positionId,bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 quantity,uint256 entryPriceE8,uint256 collateralBefore,uint256 collateralAfter,uint256 priceE8,uint256 observedAt,uint256 validUntil,bytes32 policyHash,address executor)");
    public record VerifiedSignature(String digest,String signer) {
        public String scope() {return "DIGEST_AND_EOA_SIGNATURE_ONLY_NOT_TRANSACTION_OR_CANONICAL_PROOF";}
    }
    public String intentTypeHash() {return hex(INTENT_TYPE);}
    public String reportTypeHash() {return hex(REPORT_TYPE);}
    public String intentStructHash(Intent i) {
        require(i!=null,"INTENT");
        return hex(Hash.sha3(join(INTENT_TYPE,addressWord(i.owner()),addressWord(i.relayer()),hashWord(i.operationId()),hashWord(i.positionId()),
            hashWord(i.accountRef()),hashWord(i.symbolHash()),word(i.action()),word(i.expectedVersion()),word(i.expectedCallEpoch()),word(i.side()),
            word(i.leverage()),word(i.entryPriceE8()),word(i.collateralBefore()),word(i.collateralAfter()),word(i.priceMinE8()),word(i.priceMaxE8()),
            word(i.quantityMin()),word(i.quantityMax()),word(i.nonce()),word(i.issuedAt()),word(i.deadline()),hashWord(i.policyHash()))));
    }
    public String reportStructHash(Report r) {
        require(r!=null,"REPORT");
        return hex(Hash.sha3(join(REPORT_TYPE,hashWord(r.operationId()),hashWord(r.positionId()),hashWord(r.accountRef()),hashWord(r.symbolHash()),
            word(r.action()),word(r.expectedVersion()),word(r.expectedCallEpoch()),word(r.side()),word(r.leverage()),word(r.quantity()),word(r.entryPriceE8()),
            word(r.collateralBefore()),word(r.collateralAfter()),word(r.priceE8()),word(r.observedAt()),word(r.validUntil()),hashWord(r.policyHash()),addressWord(r.executor()))));
    }
    public String intentDomain(BigInteger chainId,String controller) {return domain("TokenizedMarketCFuturesUserIntent",chainId,controller);}
    public String reportDomain(BigInteger chainId,String rules) {return domain("TokenizedMarketFutures",chainId,rules);}
    public String intentDigest(Intent i,BigInteger chainId,String controller) {return digest(intentDomain(chainId,controller),intentStructHash(i));}
    public String reportDigest(Report r,BigInteger chainId,String rules) {return digest(reportDomain(chainId,rules),reportStructHash(r));}
    public VerifiedSignature verifyIntent(Intent i,BigInteger chainId,String controller,String expectedOwner,String signature) {
        String expected=nonzero(expectedOwner);require(i!=null&&expected.equals(i.owner()),"OWNER_EXPECTATION");
        String digest=intentDigest(i,chainId,controller),signer=recover(digest,signature);
        require(signer.equals(expected),"SIGNER_MISMATCH");return new VerifiedSignature(digest,signer);
    }
    public VerifiedSignature verifyReport(Report r,BigInteger chainId,String rules,String expectedOracle,String signature) {
        String expected=nonzero(expectedOracle),digest=reportDigest(r,chainId,rules),signer=recover(digest,signature);
        require(signer.equals(expected),"SIGNER_MISMATCH");return new VerifiedSignature(digest,signer);
    }
    /** Exactly OpenZeppelin-compatible 65-byte ECDSA path: no compact signature or ERC-1271 fallback. */
    public String recover(String digest,String signature) {
        byte[] hash;
        try {hash=hashWord(digest);}catch(IllegalArgumentException invalid){throw failure("DIGEST");}
        if(signature==null||!signature.matches("0x[0-9a-fA-F]{130}"))throw failure("SIGNATURE_LENGTH");
        byte[] b=HEX.parseHex(signature.substring(2));
        BigInteger r=new BigInteger(1,Arrays.copyOfRange(b,0,32)),s=new BigInteger(1,Arrays.copyOfRange(b,32,64));
        int v=Byte.toUnsignedInt(b[64]);
        require(v==27||v==28,"SIGNATURE_V");
        require(r.signum()>0&&r.compareTo(N)<0,"SIGNATURE_R");
        require(s.signum()>0&&s.compareTo(HALF_N)<=0,"SIGNATURE_S");
        try {
            BigInteger publicKey=Sign.signedMessageHashToKey(hash,new Sign.SignatureData(b[64],Arrays.copyOfRange(b,0,32),Arrays.copyOfRange(b,32,64)));
            String address="0x"+Keys.getAddress(publicKey);require(!address.equals(ZERO_ADDRESS),"SIGNATURE_RECOVERY");return address;
        } catch(Exception recoveryFailure) {throw failure("SIGNATURE_RECOVERY");}
    }
    private String domain(String name,BigInteger chainId,String target) {
        try {CAbiModels.uint(chainId);}catch(IllegalArgumentException invalid){throw failure("CHAIN_ID");}
        require(chainId.signum()>0,"CHAIN_ID");
        return hex(Hash.sha3(join(DOMAIN_TYPE,keccakText(name),keccakText("1"),word(chainId),addressWord(nonzero(target)))));
    }
    private static String nonzero(String address) {
        String normalized;
        try {normalized=CAbiModels.address(address);}catch(IllegalArgumentException invalid){throw failure("ADDRESS");}
        require(!normalized.equals(ZERO_ADDRESS),"ADDRESS");return normalized;
    }
    private String digest(String domain,String struct) {return hex(Hash.sha3(join(new byte[]{0x19,0x01},hashWord(domain),hashWord(struct))));}
    private static byte[] keccakText(String s) {return Hash.sha3(s.getBytes(StandardCharsets.UTF_8));}
    private static byte[] hashWord(String s) {return HEX.parseHex(CAbiModels.hash(s).substring(2));}
    private static byte[] addressWord(String s) {byte[] word=new byte[32];System.arraycopy(HEX.parseHex(CAbiModels.address(s).substring(2)),0,word,12,20);return word;}
    private static byte[] word(BigInteger n) {
        CAbiModels.uint(n);byte[] raw=n.toByteArray(),word=new byte[32];int count=Math.min(raw.length,32);
        System.arraycopy(raw,raw.length-count,word,32-count,count);return word;
    }
    private static byte[] join(byte[]... chunks) {int size=0;for(byte[] c:chunks)size+=c.length;byte[] result=new byte[size];int at=0;for(byte[] c:chunks){System.arraycopy(c,0,result,at,c.length);at+=c.length;}return result;}
    private static String hex(byte[] bytes) {return "0x"+HEX.formatHex(bytes);}
    private static void require(boolean condition,String code) {if(!condition)throw failure(code);}
    private static IllegalArgumentException failure(String code) {return new IllegalArgumentException("C_EIP712_"+code);}
}
