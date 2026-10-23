package com.pricetrack.exchange.custody.proof;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.rlp.*;

class CSignedTransactionVerifierTest {
    // Public deterministic unit-test integers, never existing user/Anvil credentials.
    static final ECKeyPair TEST=ECKeyPair.create(BigInteger.valueOf(17));
    static final String SENDER="0x"+Keys.getAddress(TEST.getPublicKey());
    static final BigInteger UINT_MAX=BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    static final BigInteger N=new BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",16);
    static final HexFormat HEX=HexFormat.of();
    static final String LEGACY="0xf86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a0d39364197cfb2748c108a4394329910b568adff35651e4ecd8dacccce3b977a9a025b6b7a82d8765037e2a03d671503a9906042bc093a89929b7e9acd957587a4f";
    static final String LEGACY_HASH="0xf173e0f61cf078fb71542dc018198fb91d5f20fd3adf5fac4470b6a7a45dfffc";
    static final String TYPE2="0x02f8a9827a6980843b9aca008477359400830186a09411111111111111111111111111111111111111118083010203f838f7942222222222222222222222222222222222222222e1a0333333333333333333333333333333333333333333333333333333333333333380a0ef6c089f4f32bb11de95210b092a31d92b7a83b412154c226e4745d268c8295da079f85aa673565ecfabb63c5ca755f32472c4badc62758561655e555bf9194e2b";
    static final String TYPE2_HASH="0x2c473f34953e5c6e94da854bb4634734d20c12f43e2c322839d6a1c1be5e4bd1";
    final CSignedTransactionVerifier verifier=new CSignedTransactionVerifier();
    static BigInteger n(long n) {return BigInteger.valueOf(n);}
    static byte[] bytes(String hex) {return HEX.parseHex(hex.substring(2));}
    static String hex(byte[] bytes) {return "0x"+HEX.formatHex(bytes);}
    static String hash(byte[] raw) {return hex(Hash.sha3(raw));}
    static RlpString number(BigInteger n) {return RlpString.create(n);}
    static RlpString value(byte[] data) {return RlpString.create(data);}
    static byte[] encode(List<RlpType> fields) {return RlpEncoder.encode(new RlpList(fields));}
    List<RlpType> legacyBase(byte[] data) {return new ArrayList<>(List.of(number(n(1)),number(n(2)),number(n(100000)),value(new byte[20]),number(n(3)),value(data)));}
    List<RlpType> legacyFields(BigInteger chain,byte[] data) {
        List<RlpType> base=legacyBase(data),unsigned=new ArrayList<>(base);
        unsigned.add(number(chain));unsigned.add(number(BigInteger.ZERO));unsigned.add(number(BigInteger.ZERO));
        Sign.SignatureData signature=Sign.signMessage(Hash.sha3(encode(unsigned)),TEST,false);
        base.add(number(chain.multiply(n(2)).add(n(35+signature.getV()[0]-27))));
        base.add(number(new BigInteger(1,signature.getR())));base.add(number(new BigInteger(1,signature.getS())));return base;
    }
    List<RlpType> type2Fields(BigInteger chain,RlpType access,byte[] data) {
        List<RlpType> base=new ArrayList<>(List.of(number(chain),number(BigInteger.ZERO),number(n(1)),number(n(2)),number(n(100000)),
            value(new byte[20]),number(BigInteger.ZERO),value(data),access));
        Sign.SignatureData signature=Sign.signMessage(Hash.sha3(join(new byte[]{2},encode(base))),TEST,false);
        base.add(number(n(signature.getV()[0]-27)));base.add(number(new BigInteger(1,signature.getR())));base.add(number(new BigInteger(1,signature.getS())));return base;
    }
    byte[] type2(List<RlpType> fields) {return join(new byte[]{2},encode(fields));}
    byte[] join(byte[]... arrays) {int size=0;for(byte[] b:arrays)size+=b.length;byte[] out=new byte[size];int p=0;for(byte[] b:arrays){System.arraycopy(b,0,out,p,b.length);p+=b.length;}return out;}
    CSignedTransactionVerifier.Proof verify(byte[] raw,BigInteger chain) {return verifier.verify(hex(raw),hash(raw),chain);}
    CSignedTransactionVerifier.Failure rejected(byte[] raw,BigInteger chain) {
        return assertThrows(CSignedTransactionVerifier.Failure.class,()->verify(raw,chain));
    }
    @Test void independentLocalCastLegacyGoldenAuthenticatesHashDigestAndSender() {
        var proof=verifier.verify(LEGACY,LEGACY_HASH,n(1));
        assertEquals("0x252dae0a4b9d9b80f504f6418acd2d364c0c59cd",proof.sender());
        assertEquals("0xdaf5a779ae972f972197303d7b574746c7ef83eadac0f2791ad23db92e4c8e53",proof.signingHash());
        assertEquals(n(9),proof.nonce());assertEquals(n(21000),proof.gasLimit());
        assertEquals(new BigInteger("1000000000000000000"),proof.value());assertEquals(n(20000000000L),proof.gasPrice());
        assertEquals("0x"+"35".repeat(20),proof.to());assertEquals("0x",proof.calldata());assertTrue(proof.accessList().isEmpty());
    }
    @Test void independentLocalCastType2GoldenPreservesAccessListAndFees() {
        var proof=verifier.verify(TYPE2,TYPE2_HASH,n(31337));
        assertEquals("0x3bc8287f1d872df4217283b7920d363f13cf39d8",proof.sender());
        assertEquals("0x65d3db06f6f4c08745d0a483d10310b88876d900e3d75091f9d2e2cda1e98401",proof.signingHash());
        assertEquals(n(1000000000),proof.maxPriorityFeePerGas());assertEquals(n(2000000000),proof.maxFeePerGas());assertEquals("0x010203",proof.calldata());
        assertEquals("0x"+"22".repeat(20),proof.accessList().getFirst().address());
        assertEquals(List.of("0x"+"33".repeat(32)),proof.accessList().getFirst().storageKeys());
    }
    @Test void independentWeb3jRlpGeneratedLegacyAndType2RecoverSameTestSigner() {
        assertEquals(SENDER,verify(encode(legacyFields(n(31337),new byte[]{1,2,3})),n(31337)).sender());
        assertEquals(SENDER,verify(type2(type2Fields(n(31337),new RlpList(List.of()),new byte[]{1,2,3})),n(31337)).sender());
    }
    @Test void fullUint256ChainAnd258BitLegacyVAreNeverTruncatedToLong() {
        List<RlpType> f=legacyFields(UINT_MAX,new byte[0]);
        assertTrue(new BigInteger(1,((RlpString)f.get(6)).getBytes()).bitLength()>256);
        assertEquals(UINT_MAX,verify(encode(f),UINT_MAX).chainId());
        assertEquals(UINT_MAX,verify(type2(type2Fields(UINT_MAX,new RlpList(List.of()),new byte[0])),UINT_MAX).chainId());
    }
    @Test void expectedHashAndChainRejectReplayIdentityWithoutClaimingCanonicalInclusion() {
        assertEquals("C_TX_HASH_MISMATCH",assertThrows(CSignedTransactionVerifier.Failure.class,
            ()->verifier.verify(LEGACY,"0x"+"00".repeat(32),n(1))).getMessage());
        assertEquals("C_TX_CHAIN_MISMATCH",assertThrows(CSignedTransactionVerifier.Failure.class,
            ()->verifier.verify(LEGACY,LEGACY_HASH,n(2))).getMessage());
        assertTrue(verifier.verify(LEGACY,LEGACY_HASH,n(1)).scope().contains("NOT_RECEIPT"));
    }
    @Test void unsupportedTypedAndUnprotectedLegacyHaveExplicitUnsupportedKind() {
        for(int type:new int[]{1,3,4,0x7e}) {
            var error=rejected(new byte[]{(byte)type,(byte)0xc0},n(1));assertEquals(CSignedTransactionVerifier.FailureKind.UNSUPPORTED_INPUT,error.kind());
        }
        for(long v:new long[]{27,28}) {List<RlpType> f=legacyFields(n(1),new byte[0]);f.set(6,number(n(v)));assertEquals(CSignedTransactionVerifier.FailureKind.UNSUPPORTED_INPUT,rejected(encode(f),n(1)).kind());}
    }
    @Test void integerZeroMustBeEmptyAndLeadingZeroIntegersAreRejected() {
        for(byte[] integer:new byte[][]{new byte[]{0},new byte[]{0,1}}) {
            List<RlpType> f=legacyFields(n(1),new byte[0]);f.set(0,value(integer));assertEquals("C_TX_INTEGER_LEADING_ZERO",rejected(encode(f),n(1)).getMessage());
        }
        List<RlpType> f=legacyFields(n(1),new byte[0]);f.set(0,number(BigInteger.ZERO));
        // Signature remains a valid envelope signature for a different sender after changed nonce; no expected-sender claim here.
        assertEquals(BigInteger.ZERO,verify(encode(f),n(1)).nonce());
    }
    @Test void uintWidthsAndDerivedChainRangeAreCheckedBeforeRecovery() {
        List<RlpType> f=legacyFields(n(1),new byte[0]);f.set(2,number(UINT_MAX.add(BigInteger.ONE)));
        assertEquals("C_TX_UINT_RANGE",rejected(encode(f),n(1)).getMessage());
        f=legacyFields(n(1),new byte[0]);f.set(6,number(UINT_MAX.add(BigInteger.ONE).multiply(n(2)).add(n(35))));
        assertEquals("C_TX_CHAIN_RANGE",rejected(encode(f),n(1)).getMessage());
        List<RlpType> type=type2Fields(n(1),new RlpList(List.of()),new byte[0]);type.set(0,number(BigInteger.ZERO));
        assertEquals("C_TX_CHAIN_RANGE",rejected(type2(type),n(1)).getMessage());
    }
    @Test void targetMustBeTwentyBytesOrEmptyCreationAndCannotBeList() {
        for(byte[] to:new byte[][]{new byte[19],new byte[21]}) {List<RlpType> f=legacyFields(n(1),new byte[0]);f.set(3,value(to));assertEquals("C_TX_TO_LENGTH",rejected(encode(f),n(1)).getMessage());}
        List<RlpType> f=legacyFields(n(1),new byte[0]);f.set(3,value(new byte[0]));assertTrue(verify(encode(f),n(1)).contractCreation());
        f.set(3,new RlpList(List.of()));assertEquals("C_TX_EXPECTED_BYTES",rejected(encode(f),n(1)).getMessage());
    }
    @Test void parityAndScalarRangesAndLowSAreMandatory() {
        for(int parity:new int[]{2,27,28}) {List<RlpType> f=type2Fields(n(1),new RlpList(List.of()),new byte[0]);f.set(9,number(n(parity)));assertEquals("C_TX_Y_PARITY",rejected(type2(f),n(1)).getMessage());}
        for(int field:new int[]{7,8})for(BigInteger bad:List.of(BigInteger.ZERO,N)) {
            List<RlpType> f=legacyFields(n(1),new byte[0]);f.set(field,number(bad));assertEquals(CSignedTransactionVerifier.FailureKind.SIGNATURE_INVALID,rejected(encode(f),n(1)).kind());
        }
        List<RlpType> f=legacyFields(n(1),new byte[0]);BigInteger s=new BigInteger(1,((RlpString)f.get(8)).getBytes());f.set(8,number(N.subtract(s)));
        assertEquals(CSignedTransactionVerifier.FailureKind.SIGNATURE_INVALID,rejected(encode(f),n(1)).kind());
    }
    @Test void accessListShapeAddressAndStorageWordLengthsAreStrict() {
        List<RlpType> malformed=List.of(value(new byte[0]),new RlpList(List.of(value(new byte[20]))),
            new RlpList(List.of(value(new byte[19]),new RlpList(List.of()))),
            new RlpList(List.of(value(new byte[20]),value(new byte[0]))),
            new RlpList(List.of(value(new byte[20]),new RlpList(List.of(value(new byte[31]))))));
        for(RlpType row:malformed)assertEquals(CSignedTransactionVerifier.FailureKind.MALFORMED_INPUT,rejected(type2(type2Fields(n(1),new RlpList(List.of(row)),new byte[0])),n(1)).kind());
        assertEquals("C_TX_ACCESS_LIST",rejected(type2(type2Fields(n(1),value(new byte[0]),new byte[0])),n(1)).getMessage());
    }
    @Test void accessListDuplicatesAndOrderArePreservedNotSilentlyDeduplicated() {
        RlpType row=new RlpList(List.of(value(new byte[20]),new RlpList(List.of(value(new byte[32]),value(new byte[32])))));
        var proof=verify(type2(type2Fields(n(1),new RlpList(List.of(row,row)),new byte[0])),n(1));
        assertEquals(2,proof.accessList().size());assertEquals(2,proof.accessList().getFirst().storageKeys().size());
        assertThrows(UnsupportedOperationException.class,()->proof.accessList().clear());
    }
    @Test void accessEntryStorageAndCalldataCapsBoundWork() {
        RlpType row=new RlpList(List.of(value(new byte[20]),new RlpList(List.of())));
        assertEquals(CSignedTransactionVerifier.FailureKind.RESOURCE_LIMIT,rejected(type2(type2Fields(n(1),new RlpList(Collections.nCopies(65,row)),new byte[0])),n(1)).kind());
        RlpType keys=new RlpList(List.of(value(new byte[20]),new RlpList(Collections.nCopies(257,value(new byte[32])))));
        assertEquals(CSignedTransactionVerifier.FailureKind.RESOURCE_LIMIT,rejected(type2(type2Fields(n(1),new RlpList(List.of(keys)),new byte[0])),n(1)).kind());
        assertEquals("C_TX_CALLDATA",rejected(encode(legacyFields(n(1),new byte[CSignedTransactionVerifier.MAX_CALLDATA_BYTES+1])),n(1)).getMessage());
    }
    @Test void shortAndLongRlpPayloadBoundaryFiftyFiveFiftySixIsCanonical() {
        for(int size:new int[]{0,1,55,56,255,256}) {
            byte[] payload=new byte[size];Arrays.fill(payload,(byte)0xa5);
            assertEquals(hex(payload),verify(encode(legacyFields(n(1),payload)),n(1)).calldata());
        }
    }
    @Test void noncanonicalSingleByteAndOverlongLengthPrefixAreRejected() {
        byte[] original=bytes(LEGACY);
        byte[] overlongNonce=join(new byte[]{(byte)0xf8,(byte)0x6d,(byte)0x81,9},Arrays.copyOfRange(original,3,original.length));
        assertEquals("C_TX_RLP_SINGLE_BYTE",rejected(overlongNonce,n(1)).getMessage());
        byte[] leadingLength=join(new byte[]{(byte)0xf9,0,(byte)0x6c},Arrays.copyOfRange(original,2,original.length));
        assertEquals("C_TX_RLP_LENGTH_ZERO",rejected(leadingLength,n(1)).getMessage());
        assertEquals("C_TX_RLP_LONG_FORM",rejected(new byte[]{(byte)0xf8,1,(byte)0x80},n(1)).getMessage());
        assertEquals("C_TX_RLP_LONG_FORM",rejected(new byte[]{(byte)0xb8,1,(byte)0x80},n(1)).getMessage());
    }
    @Test void rlpTrailingTruncationRootAndExtraFieldsAreRejected() {
        byte[] raw=bytes(LEGACY);assertEquals("C_TX_RLP_TRAILING",rejected(join(raw,new byte[]{0}),n(1)).getMessage());
        for(int end:new int[]{1,2,3,raw.length-1})rejected(Arrays.copyOf(raw,end),n(1));
        assertEquals("C_TX_RLP_ROOT",rejected(new byte[]{(byte)0x80},n(1)).getMessage());
        List<RlpType> f=legacyFields(n(1),new byte[0]);f.add(number(BigInteger.ZERO));assertEquals("C_TX_FIELD_COUNT",rejected(encode(f),n(1)).getMessage());
    }
    @Test void nestedListsAndNodeBudgetsCannotTriggerUnboundedRecursion() {
        RlpType nested=value(new byte[0]);for(int k=0;k<8;k++)nested=new RlpList(List.of(nested));
        assertEquals(CSignedTransactionVerifier.FailureKind.RESOURCE_LIMIT,rejected(RlpEncoder.encode(nested),n(1)).kind());
        byte[] many=encode(Collections.nCopies(CSignedTransactionVerifier.MAX_RLP_NODES+1,value(new byte[0])));
        assertEquals("C_TX_RLP_NODES",rejected(many,n(1)).getMessage());
        byte[] hugeLength={(byte)0xbf,0x7f,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff};
        assertEquals(CSignedTransactionVerifier.FailureKind.RESOURCE_LIMIT,rejected(hugeLength,n(1)).kind());
    }
    @Test void oversizedRawAndMalformedExpectedIdentityUseSafeCodes() {
        assertEquals(CSignedTransactionVerifier.FailureKind.RESOURCE_LIMIT,assertThrows(CSignedTransactionVerifier.Failure.class,
            ()->verifier.verify("0x"+"00".repeat(CSignedTransactionVerifier.MAX_RAW_BYTES+1),LEGACY_HASH,n(1))).kind());
        for(String bad:List.of("", "0x1", "0xgg"))assertThrows(CSignedTransactionVerifier.Failure.class,()->verifier.verify(bad,LEGACY_HASH,n(1)));
        for(BigInteger bad:List.of(BigInteger.ZERO,n(-1),UINT_MAX.add(BigInteger.ONE)))assertThrows(CSignedTransactionVerifier.Failure.class,()->verifier.verify(LEGACY,LEGACY_HASH,bad));
        var failure=assertThrows(CSignedTransactionVerifier.Failure.class,()->verifier.verify(LEGACY,"DO_NOT_ECHO",n(1)));
        assertEquals("C_TX_EXPECTED_HASH",failure.getMessage());assertNull(failure.getCause());
    }
    @Test void safeProofStringNeverContainsCalldataOrSignatureMaterial() {
        String sentinel="deadbeefcafef00d112233445566778899aabbccddeeff";
        var proof=verify(encode(legacyFields(n(1),HEX.parseHex(sentinel))),n(1));
        assertEquals("0x"+sentinel,proof.calldata());
        assertFalse(proof.toString().contains(sentinel));assertFalse(proof.toString().contains(proof.calldata()));
        assertTrue(proof.toString().contains("calldataBytes="));assertTrue(proof.scope().contains("NOT_RECEIPT"));
    }
    @Test void tamperedRawCannotReuseOriginalExpectedHashAndNoNonceStateIsMutated() {
        byte[] changed=bytes(LEGACY);changed[changed.length-1]^=1;
        assertEquals("C_TX_HASH_MISMATCH",assertThrows(CSignedTransactionVerifier.Failure.class,
            ()->verifier.verify(hex(changed),LEGACY_HASH,n(1))).getMessage());
        assertEquals(verifier.verify(LEGACY,LEGACY_HASH,n(1)),verifier.verify(LEGACY,LEGACY_HASH,n(1)));
    }
}
