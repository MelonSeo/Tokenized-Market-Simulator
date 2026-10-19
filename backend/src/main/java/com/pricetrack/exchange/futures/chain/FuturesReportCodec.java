package com.pricetrack.exchange.futures.chain;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

/** Exact static Solidity tuple and EIP-712 domain; independent of the spot Oracle domain. */
public final class FuturesReportCodec {
    public static final String TYPE = "FuturesReport(bytes32 operationId,bytes32 positionId,bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 quantity,uint256 entryPriceE8,uint256 collateralBefore,uint256 collateralAfter,uint256 priceE8,uint256 observedAt,uint256 validUntil,bytes32 policyHash,address executor)";
    private static final String DOMAIN = "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)";
    private static final String[] FUNCTIONS = {"open", "topUp", "startMarginCall", "clearMarginCall", "close", "liquidate"};
    private FuturesReportCodec() {}

    public static List<Type> fields(FuturesReport r) {
        if (r.action()<0 || r.action()>=FUNCTIONS.length || r.side()<0 || r.side()>1
                || !Set.of(2,5,10).contains(r.leverage())) throw new IllegalArgumentException("INVALID_REPORT_ENUM");
        return List.of(bytes(r.operationId()), bytes(r.positionId()), bytes(r.accountRef()), bytes(r.symbolHash()),
            new Uint8(r.action()), uint(r.expectedVersion()), uint(r.expectedCallEpoch()), new Uint8(r.side()),
            new Uint8(r.leverage()), uint(r.quantity()), uint(r.entryPriceE8()), uint(r.collateralBefore()),
            uint(r.collateralAfter()), uint(r.priceE8()), uint(r.observedAt()), uint(r.validUntil()),
            bytes(r.policyHash()), address(r.executor()));
    }

    public static byte[] digest(FuturesReport r, BigInteger chainId, String engine) {
        byte[] domain = Hash.sha3(join(hash(DOMAIN), hash("TokenizedMarketFutures"), hash("1"),
            word(uint(chainId)), word(address(engine))));
        byte[] struct = Hash.sha3(join(hash(TYPE), Numeric.hexStringToByteArray(FunctionEncoder.encodeConstructor(fields(r)))));
        return Hash.sha3(join(new byte[]{0x19,0x01}, domain, struct));
    }

    public static String sign(FuturesReport r, BigInteger chainId, String engine, ECKeyPair key) {
        var signature = Sign.signMessage(digest(r,chainId,engine),key,false);
        return Numeric.toHexString(join(signature.getR(),signature.getS(),signature.getV()));
    }

    public static boolean signedBy(FuturesReport r, BigInteger chainId, String engine, String signature, String signer) {
        try {
            if (signature==null || !signature.matches("0x[0-9a-fA-F]{130}")) return false;
            byte[] b=Numeric.hexStringToByteArray(signature);
            var s=new Sign.SignatureData(b[64],Arrays.copyOfRange(b,0,32),Arrays.copyOfRange(b,32,64));
            BigInteger lowS=Numeric.toBigInt(s.getS());
            if ((b[64]!=27 && b[64]!=28) || lowS.signum()<=0 || lowS.compareTo(Sign.CURVE_PARAMS.getN().shiftRight(1))>0) return false;
            return ("0x"+Keys.getAddress(Sign.signedMessageHashToKey(digest(r,chainId,engine),s))).equalsIgnoreCase(signer);
        } catch (Exception invalid) { return false; }
    }

    public static String encode(FuturesReport r, String signature) {
        if (signature==null || !signature.matches("0x[0-9a-fA-F]{130}")) throw new IllegalArgumentException("INVALID_REPORT_SIGNATURE");
        return FunctionEncoder.encode(new Function(FUNCTIONS[r.action()],
            List.of(new StaticStruct(fields(r)),new DynamicBytes(Numeric.hexStringToByteArray(signature))),List.of()));
    }
    private static Bytes32 bytes(String s) {
        if (s==null || !s.matches("0x[0-9a-fA-F]{64}")) throw new IllegalArgumentException("INVALID_BYTES32");
        return new Bytes32(Numeric.hexStringToByteArray(s));
    }
    private static Address address(String s) {
        if (s==null || !s.matches("0x[0-9a-fA-F]{40}")) throw new IllegalArgumentException("INVALID_ADDRESS");
        return new Address(s);
    }
    private static Uint256 uint(BigInteger n) {
        if (n==null || n.signum()<0 || n.bitLength()>256) throw new IllegalArgumentException("INVALID_UINT256");
        return new Uint256(n);
    }
    private static byte[] word(Type t) { return Numeric.hexStringToByteArray(FunctionEncoder.encodeConstructor(List.of(t))); }
    private static byte[] hash(String s) { return Hash.sha3(s.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] join(byte[]... arrays) {
        var out=new ByteArrayOutputStream(); for(byte[] a:arrays) out.writeBytes(a); return out.toByteArray();
    }
}
