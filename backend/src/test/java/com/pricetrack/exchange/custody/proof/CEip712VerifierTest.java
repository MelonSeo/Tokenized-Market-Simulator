package com.pricetrack.exchange.custody.proof;

import static org.junit.jupiter.api.Assertions.*;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.abi.CAbiModels.Report;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

class CEip712VerifierTest {
    // Public, tiny, deterministic unit-test integers only. Never a user's key or live Anvil credential.
    static final ECKeyPair USER=ECKeyPair.create(BigInteger.valueOf(17)), ORACLE=ECKeyPair.create(BigInteger.valueOf(23));
    static final String OWNER="0x"+Keys.getAddress(USER.getPublicKey()), SIGNER="0x"+Keys.getAddress(ORACLE.getPublicKey());
    static final String CONTROLLER="0x"+"33".repeat(20), RULES="0x"+"44".repeat(20);
    static final BigInteger CHAIN=BigInteger.valueOf(31337), N=new BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",16);
    static final HexFormat HEX=HexFormat.of();
    final CEip712Verifier verifier=new CEip712Verifier();
    static BigInteger n(long n) {return BigInteger.valueOf(n);}
    static String a(BigInteger n) {return "0x"+String.format("%040x",n);}
    static String h(BigInteger n) {return "0x"+String.format("%064x",n);}
    BigInteger[] goldenIntentWords() {return new BigInteger[]{new BigInteger("11".repeat(20),16),new BigInteger("22".repeat(20),16),n(3),n(4),n(5),n(6),n(0),n(0),n(0),n(1),n(10),n(0),n(0),n(100).multiply(BigInteger.TEN.pow(18)),n(99_00000000L),n(101_00000000L),n(9).multiply(BigInteger.TEN.pow(18)),n(11).multiply(BigInteger.TEN.pow(18)),n(7),n(1000),n(1120),n(8)};}
    BigInteger[] reportWords() {return Arrays.stream(new long[]{1,2,3,4,4,5,6,1,10,9,10,11,12,13,14,44,17,18}).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new);}
    Intent intent(BigInteger[] w) {return new Intent(a(w[0]),a(w[1]),h(w[2]),h(w[3]),h(w[4]),h(w[5]),w[6],w[7],w[8],w[9],w[10],w[11],w[12],w[13],w[14],w[15],w[16],w[17],w[18],w[19],w[20],h(w[21]));}
    Report report(BigInteger[] w) {return new Report(h(w[0]),h(w[1]),h(w[2]),h(w[3]),w[4],w[5],w[6],w[7],w[8],w[9],w[10],w[11],w[12],w[13],w[14],w[15],h(w[16]),a(w[17]));}
    BigInteger[] userWords() {BigInteger[] w=goldenIntentWords();w[0]=new BigInteger(OWNER.substring(2),16);return w;}
    Intent userIntent() {return intent(userWords());}
    Report report() {return report(reportWords());}
    String serialize(Sign.SignatureData s) {return "0x"+HEX.formatHex(s.getR())+HEX.formatHex(s.getS())+HEX.formatHex(s.getV());}
    String sign(String digest,ECKeyPair key) {return serialize(Sign.signMessage(HEX.parseHex(digest.substring(2)),key,false));}
    String userSignature(Intent i) {return sign(verifier.intentDigest(i,CHAIN,CONTROLLER),USER);}
    byte[] word(BigInteger n) {byte[] raw=n.toByteArray(),out=new byte[32];int len=Math.min(32,raw.length);System.arraycopy(raw,raw.length-len,out,32-len,len);return out;}
    byte[] join(byte[]... arrays) {int size=0;for(byte[] a:arrays)size+=a.length;byte[] out=new byte[size];int p=0;for(byte[] a:arrays){System.arraycopy(a,0,out,p,a.length);p+=a.length;}return out;}
    String foreignDigest(String struct,String name,String version,String target) {
        byte[] domain=Hash.sha3(join(Hash.sha3("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".getBytes(StandardCharsets.UTF_8)),
            Hash.sha3(name.getBytes(StandardCharsets.UTF_8)),Hash.sha3(version.getBytes(StandardCharsets.UTF_8)),word(CHAIN),word(new BigInteger(target.substring(2),16))));
        return "0x"+HEX.formatHex(Hash.sha3(join(new byte[]{0x19,0x01},domain,HEX.parseHex(struct.substring(2)))));
    }
    void rejectUser(Intent i,String signature) {assertThrows(IllegalArgumentException.class,()->verifier.verifyIntent(i,CHAIN,CONTROLLER,OWNER,signature));}

    @Test void independentTwentyTwoFieldIntentGoldenMatchesAllHashStages() {
        Intent i=intent(goldenIntentWords());
        assertEquals("0xae1bef5d472f4b46787dd0444f164ec31ebf4fc901b01ddf69c8ddf6a1733ff7",verifier.intentTypeHash());
        assertEquals("0x08379fb7f965c1760c22f2c0ce8cec533b216d3fb5622bfc38f71c686e68b10f",verifier.intentStructHash(i));
        assertEquals("0xa81cf76c7e65e2d4a39e6d9637dcf2f104fb9d94d819bdddfab0f733f3bc2c5a",verifier.intentDomain(CHAIN,CONTROLLER));
        assertEquals("0xb81cb4bf2b9bad10a2613da0ec1df89549be8aaf991f3cf4149ef39ca02a3e67",verifier.intentDigest(i,CHAIN,CONTROLLER));
    }
    @Test void independentEighteenFieldReportGoldenMatchesAllHashStages() {
        assertEquals("0x11abeea8e2bb530223ac3eff4001f9e95407652ac92788ceffc5c808ead6e6c6",verifier.reportTypeHash());
        assertEquals("0x347554e5d6c6e43609b2d5d1e9e357e68fe0471638fac42929926046c5f25434",verifier.reportStructHash(report()));
        assertEquals("0x65543a891ab621ab903839b38ef11462507bd2d2bd7b0809c6aea919d3fe62f2",verifier.reportDomain(CHAIN,RULES));
        assertEquals("0x9f4e33687a94c533d176a054aaf5d435999bd713e385eb52b5b064daf7fb9a30",verifier.reportDigest(report(),CHAIN,RULES));
    }
    @Test void validUserEoaSignatureRecoversExplicitOwner() {
        Intent i=userIntent();String signature=userSignature(i);
        var proof=verifier.verifyIntent(i,CHAIN,CONTROLLER,OWNER,signature);
        assertEquals(OWNER,proof.signer());assertEquals(verifier.intentDigest(i,CHAIN,CONTROLLER),proof.digest());
        assertTrue(proof.scope().contains("NOT_TRANSACTION"));
    }
    @Test void validOracleSignatureRecoversOnlyExplicitExpectedSigner() {
        String digest=verifier.reportDigest(report(),CHAIN,RULES),signature=sign(digest,ORACLE);
        assertEquals(SIGNER,verifier.verifyReport(report(),CHAIN,RULES,SIGNER,signature).signer());
        assertThrows(IllegalArgumentException.class,()->verifier.verifyReport(report(),CHAIN,RULES,OWNER,signature));
    }
    @Test void everyIntentFieldMutationBreaksOriginalSignature() {
        BigInteger[] original=userWords();Intent i=intent(original);String digest=verifier.intentDigest(i,CHAIN,CONTROLLER),signature=userSignature(i);
        for(int k=0;k<22;k++) {
            BigInteger[] changed=original.clone();changed[k]=changed[k].add(BigInteger.ONE);Intent mutated=intent(changed);
            assertNotEquals(digest,verifier.intentDigest(mutated,CHAIN,CONTROLLER),"field="+k);rejectUser(mutated,signature);
            assertEquals(digest,verifier.intentDigest(i,CHAIN,CONTROLLER));
        }
    }
    @Test void everyReportFieldMutationBreaksOriginalOracleSignature() {
        BigInteger[] original=reportWords();String digest=verifier.reportDigest(report(original),CHAIN,RULES),signature=sign(digest,ORACLE);
        for(int k=0;k<18;k++) {
            BigInteger[] changed=original.clone();changed[k]=changed[k].add(BigInteger.ONE);Report mutated=report(changed);
            assertNotEquals(digest,verifier.reportDigest(mutated,CHAIN,RULES),"field="+k);
            assertThrows(IllegalArgumentException.class,()->verifier.verifyReport(mutated,CHAIN,RULES,SIGNER,signature));
        }
    }
    @Test void userChainAndVerifyingContractCannotBeReused() {
        Intent i=userIntent();String signature=userSignature(i);
        assertThrows(IllegalArgumentException.class,()->verifier.verifyIntent(i,CHAIN.add(BigInteger.ONE),CONTROLLER,OWNER,signature));
        assertThrows(IllegalArgumentException.class,()->verifier.verifyIntent(i,CHAIN,RULES,OWNER,signature));
    }
    @Test void reportChainAndRulesAddressCannotBeReused() {
        String signature=sign(verifier.reportDigest(report(),CHAIN,RULES),ORACLE);
        assertThrows(IllegalArgumentException.class,()->verifier.verifyReport(report(),CHAIN.add(BigInteger.ONE),RULES,SIGNER,signature));
        assertThrows(IllegalArgumentException.class,()->verifier.verifyReport(report(),CHAIN,CONTROLLER,SIGNER,signature));
    }
    @Test void oldBNameVersionsAndWrongCVersionAreNotCApproval() {
        Intent i=userIntent();String struct=verifier.intentStructHash(i);
        for(String version:List.of("1","2"))rejectUser(i,sign(foreignDigest(struct,"TokenizedMarketFuturesUserIntent",version,CONTROLLER),USER));
        rejectUser(i,sign(foreignDigest(struct,"TokenizedMarketCFuturesUserIntent","2",CONTROLLER),USER));
    }
    @Test void userAndPriceDomainsStayDistinctEvenAtSameAddress() {
        Intent i=userIntent();String foreign=foreignDigest(verifier.intentStructHash(i),"TokenizedMarketFutures","1",CONTROLLER);
        rejectUser(i,sign(foreign,USER));
        assertNotEquals(verifier.intentDomain(CHAIN,CONTROLLER),verifier.reportDomain(CHAIN,CONTROLLER));
    }
    @Test void highSMalleationIsRejectedEvenWithFlippedV() {
        Intent i=userIntent();String digest=verifier.intentDigest(i,CHAIN,CONTROLLER);
        byte[] b=HEX.parseHex(userSignature(i).substring(2));BigInteger s=new BigInteger(1,Arrays.copyOfRange(b,32,64));
        System.arraycopy(word(N.subtract(s)),0,b,32,32);b[64]=(byte)(b[64]==27?28:27);
        IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->verifier.recover(digest,"0x"+HEX.formatHex(b)));
        assertEquals("C_EIP712_SIGNATURE_S",failure.getMessage());
    }
    @Test void zeroOutOfRangeRValuesAreRejectedBeforeRecovery() {
        Intent i=userIntent();String digest=verifier.intentDigest(i,CHAIN,CONTROLLER);
        for(BigInteger r:List.of(BigInteger.ZERO,N,N.add(BigInteger.ONE))) {
            byte[] b=HEX.parseHex(userSignature(i).substring(2));System.arraycopy(word(r),0,b,0,32);
            assertEquals("C_EIP712_SIGNATURE_R",assertThrows(IllegalArgumentException.class,()->verifier.recover(digest,"0x"+HEX.formatHex(b))).getMessage());
        }
    }
    @Test void zeroHighAndOutOfRangeSValuesAreRejectedBeforeRecovery() {
        Intent i=userIntent();String digest=verifier.intentDigest(i,CHAIN,CONTROLLER);
        for(BigInteger s:List.of(BigInteger.ZERO,N.shiftRight(1).add(BigInteger.ONE),N)) {
            byte[] b=HEX.parseHex(userSignature(i).substring(2));System.arraycopy(word(s),0,b,32,32);
            assertEquals("C_EIP712_SIGNATURE_S",assertThrows(IllegalArgumentException.class,()->verifier.recover(digest,"0x"+HEX.formatHex(b))).getMessage());
        }
    }
    @Test void vMustBeExactlyTwentySevenOrTwentyEight() {
        Intent i=userIntent();String digest=verifier.intentDigest(i,CHAIN,CONTROLLER);
        for(int v:new int[]{0,1,26,29,37,255}) {
            byte[] b=HEX.parseHex(userSignature(i).substring(2));b[64]=(byte)v;
            assertEquals("C_EIP712_SIGNATURE_V",assertThrows(IllegalArgumentException.class,()->verifier.recover(digest,"0x"+HEX.formatHex(b))).getMessage());
        }
    }
    @Test void signatureLengthHexAndCompactSignaturesAreNotSilentlyNormalized() {
        String digest=verifier.intentDigest(userIntent(),CHAIN,CONTROLLER);
        for(String signature:List.of("0x","0x"+"01".repeat(64),"0x"+"01".repeat(66),"0x"+"gg".repeat(65),"0x1"))
            assertEquals("C_EIP712_SIGNATURE_LENGTH",assertThrows(IllegalArgumentException.class,()->verifier.recover(digest,signature)).getMessage());
    }
    @Test void digestMustBeExactlyBytesThirtyTwo() {
        String signature=userSignature(userIntent());
        for(String digest:List.of("0x","0x01","0x"+"00".repeat(33),"not a digest"))
            assertEquals("C_EIP712_DIGEST",assertThrows(IllegalArgumentException.class,()->verifier.recover(digest,signature)).getMessage());
    }
    @Test void chainAndVerifyingContractMustBeNonzeroAndProperlySized() {
        for(BigInteger chain:List.of(BigInteger.ZERO,n(-1),CAbiModels.UINT_MAX.add(BigInteger.ONE)))
            assertEquals("C_EIP712_CHAIN_ID",assertThrows(IllegalArgumentException.class,()->verifier.intentDomain(chain,CONTROLLER)).getMessage());
        for(String contract:List.of("0x"+"0".repeat(40),"0x1234","secret-not-address"))
            assertEquals("C_EIP712_ADDRESS",assertThrows(IllegalArgumentException.class,()->verifier.reportDomain(CHAIN,contract)).getMessage());
    }
    @Test void explicitExpectedOwnerCannotBeSubstitutedBySuccessfulOtherSignature() {
        Intent i=userIntent();String valid=userSignature(i);
        assertEquals("C_EIP712_OWNER_EXPECTATION",assertThrows(IllegalArgumentException.class,()->verifier.verifyIntent(i,CHAIN,CONTROLLER,SIGNER,valid)).getMessage());
        rejectUser(i,sign(verifier.intentDigest(i,CHAIN,CONTROLLER),ORACLE));
    }
    @Test void personalMessagePrefixIsNotAcceptedAsTypedDataSignature() {
        Intent i=userIntent();String digest=verifier.intentDigest(i,CHAIN,CONTROLLER);
        rejectUser(i,serialize(Sign.signPrefixedMessage(HEX.parseHex(digest.substring(2)),USER)));
    }
    @Test void fullUint256PrecisionAndUint8BoundaryAreHashedWithoutNarrowing() {
        BigInteger[] words=userWords();words[18]=CAbiModels.UINT_MAX;words[6]=n(255);
        Intent i=intent(words);String digest=verifier.intentDigest(i,CAbiModels.UINT_MAX,CONTROLLER);
        assertEquals(OWNER,verifier.verifyIntent(i,CAbiModels.UINT_MAX,CONTROLLER,OWNER,sign(digest,USER)).signer());
    }
    @Test void signatureSuccessDoesNotClaimDeadlineEconomicsOrTransactionApproval() {
        BigInteger[] words=userWords();words[20]=n(999);Intent expiredOrInvalidWindow=intent(words);
        var proof=verifier.verifyIntent(expiredOrInvalidWindow,CHAIN,CONTROLLER,OWNER,userSignature(expiredOrInvalidWindow));
        assertEquals(OWNER,proof.signer());assertTrue(proof.scope().contains("NOT_TRANSACTION_OR_CANONICAL_PROOF"));
    }
    @Test void malformedInputFailureCodesNeverEchoSignatureOrDigestMaterial() {
        String sensitive="DO_NOT_ECHO_SIGNATURE_MATERIAL";
        IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->verifier.recover(verifier.intentDigest(userIntent(),CHAIN,CONTROLLER),sensitive));
        assertTrue(failure.getMessage().matches("C_EIP712_[A-Z_]+"));assertFalse(failure.getMessage().contains(sensitive));assertNull(failure.getCause());
    }
}
