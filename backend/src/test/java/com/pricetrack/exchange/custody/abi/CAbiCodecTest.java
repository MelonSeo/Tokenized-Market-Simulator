package com.pricetrack.exchange.custody.abi;

import static com.pricetrack.exchange.custody.abi.CAbiModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

class CAbiCodecTest {
    final CAbiCodec codec=new CAbiCodec();
    static BigInteger n(long n) {return BigInteger.valueOf(n);}
    static String a(int n) {return "0x"+String.format("%040x",n);}
    static String h(int n) {return "0x"+String.format("%064x",n);}
    Report report() {return new Report(h(1),h(2),h(3),h(4),n(4),n(5),n(6),n(1),n(10),n(9),n(10),n(11),n(12),n(13),n(14),n(44),h(17),a(18));}
    Intent intent() {return new Intent(a(17),a(34),h(3),h(4),h(5),h(6),n(4),n(7),n(8),n(1),n(10),n(12),n(13),n(14),n(15),n(16),n(17),n(18),n(19),n(20),n(21),h(22));}
    Direct direct() {return new Direct(report(),"0x0102030405",intent());}
    Relayed relayed() {return new Relayed(report(),"0x0102030405",intent(),"0xaabbcc");}
    String sha(byte[] data) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    byte[] word(byte[] original,int index,BigInteger value) {
        byte[] out=original.clone(),raw=value.toByteArray();Arrays.fill(out,4+index*32,4+(index+1)*32,(byte)0);
        int count=Math.min(raw.length,32);System.arraycopy(raw,raw.length-count,out,4+(index+1)*32-count,count);return out;
    }
    void reject(Target target,byte[] input) {assertThrows(IllegalArgumentException.class,()->codec.decode(target,input));}
    void round(Call call) {assertEquals(call,codec.decode(target(call),codec.encode(call)));}

    @Test void independentCastDirectCalldataGoldenCoversEveryReportAndIntentField() throws Exception {
        byte[] encoded=codec.encodeBytes(direct());
        assertEquals(1380,encoded.length);
        assertEquals("7802c5aed14c5e4ef65a17168b3f1e9842efd7975f91738d7eb741553597ecd3",sha(encoded));
        assertEquals(direct(),codec.decode(Target.CONTROLLER,encoded));
    }
    @Test void independentCastRelayedCalldataGoldenCoversBothDynamicSignatures() throws Exception {
        byte[] encoded=codec.encodeBytes(relayed());
        assertEquals(1476,encoded.length);
        assertEquals("252f9fe57b5e6c61ea7b2992031e0ff4b42ac189871329e3a79ebdf70d47b391",sha(encoded));
        assertEquals(relayed(),codec.decode(Target.CONTROLLER,encoded));
    }
    @Test void independentCastCancelGoldenIsDecodedAndEncodedExactly() throws Exception {
        String golden="0xcfdd7aea0000000000000000000000000000000000000000000000000000000000000007";
        assertEquals(new Cancel(n(7)),codec.decode(Target.CONTROLLER,golden));assertEquals(golden,codec.encode(new Cancel(n(7))));
        assertEquals("f1549845a83b6a9488866380cddb375cb1f98bedc77ba72714af34079c82f60d",sha(codec.encodeBytes(new Cancel(n(7)))));
    }
    @Test void allSupportedSelectorsMatchIndependentLocalCastSigConstants() {
        Map<Call,String> expected=new LinkedHashMap<>();expected.put(direct(),"c24ad93a");expected.put(relayed(),"b6657fd9");
        expected.put(new Risk(RiskAction.START_CALL,report(),"0x"),"c5eeafdc");
        expected.put(new Risk(RiskAction.CLEAR_CALL,report(),"0x"),"aade63fb");
        expected.put(new Risk(RiskAction.LIQUIDATE,report(),"0x"),"3f23aa97");
        expected.put(new Cancel(n(1)),"cfdd7aea");expected.put(new Pause(true),"5fefcf07");
        expected.put(new VaultAmount(VaultAction.DEPOSIT,n(1)),"b6b55f25");expected.put(new VaultAmount(VaultAction.WITHDRAW,n(1)),"2e1a7d4d");
        expected.put(new VaultAmount(VaultAction.FUND_HOUSE,n(1)),"8746cf98");expected.put(new ProcessClaim(),"da28fbe7");
        expected.put(new Transfer(a(1),n(1)),"a9059cbb");expected.put(new Approve(a(1),n(1)),"095ea7b3");
        expected.put(new TransferFrom(a(1),a(2),n(1)),"23b872dd");
        expected.forEach((call,selector)->{assertEquals("0x"+selector,codec.encode(call).substring(0,10));round(call);});
    }
    @Test void zeroAddressesAndZeroAmountsRemainSyntacticallyValidNotEconomicallyApproved() {
        round(new Transfer(a(0),BigInteger.ZERO));round(new Approve(a(0),UINT_MAX));
        round(new TransferFrom(a(0),a(0),BigInteger.ZERO));round(new VaultAmount(VaultAction.DEPOSIT,BigInteger.ZERO));
    }
    @Test void uint256MaximumAndLargeExactAmountsNeverUseFloatingPoint() {
        round(new Cancel(UINT_MAX));round(new Transfer(a(1),UINT_MAX));
        round(new VaultAmount(VaultAction.FUND_HOUSE,BigInteger.TEN.pow(25).add(BigInteger.ONE)));
        assertThrows(IllegalArgumentException.class,()->new Cancel(n(-1)));
        assertThrows(IllegalArgumentException.class,()->new Cancel(UINT_MAX.add(BigInteger.ONE)));
    }
    @Test void allUint8WordsRejectNonzeroUpperBitsRatherThanTruncating() {
        byte[] data=codec.encodeBytes(direct());
        for(int index:new int[]{4,7,8,25,28,29})reject(Target.CONTROLLER,word(data,index,n(256)));
        // 255 is valid ABI uint8 syntax even though financial policy later rejects it.
        for(int index:new int[]{4,7,8,25,28,29}) {
            byte[] syntactic=word(data,index,n(255));assertArrayEquals(syntactic,codec.encodeBytes(codec.decode(Target.CONTROLLER,syntactic)));
        }
    }
    @Test void typedModelsRejectOutOfRangeUint8AndMalformedFieldWidths() {
        Report r=report();
        assertThrows(IllegalArgumentException.class,()->new Report(r.operationId(),r.positionId(),r.accountRef(),r.symbolHash(),n(256),r.expectedVersion(),r.expectedCallEpoch(),r.side(),r.leverage(),r.quantity(),r.entryPriceE8(),r.collateralBefore(),r.collateralAfter(),r.priceE8(),r.observedAt(),r.validUntil(),r.policyHash(),r.executor()));
        assertThrows(IllegalArgumentException.class,()->new Transfer("0x1234",n(1)));
        assertThrows(IllegalArgumentException.class,()->CAbiModels.hash("0x01"));
    }
    @Test void addressHighTwelveBytesMustBeZeroEverywhere() {
        byte[] data=codec.encodeBytes(direct());
        for(int index:new int[]{17,19,20}) {byte[] bad=data.clone();bad[4+index*32]=1;reject(Target.CONTROLLER,bad);}
        byte[] transfer=codec.encodeBytes(new TransferFrom(a(1),a(2),n(3)));
        for(int index:new int[]{0,1}) {byte[] bad=transfer.clone();bad[4+index*32+11]=1;reject(Target.TOKEN,bad);}
    }
    @Test void boolRejectsValuesOtherThanZeroOrOne() {
        round(new Pause(false));round(new Pause(true));
        reject(Target.CONTROLLER,word(codec.encodeBytes(new Pause(true)),0,n(2)));
        reject(Target.CONTROLLER,word(codec.encodeBytes(new Pause(true)),0,UINT_MAX));
    }
    @Test void emptyBoundaryAndActualSignatureLengthsAreCanonical() {
        for(int size:new int[]{0,1,31,32,33,64,65,512}) {
            String sig="0x"+"ab".repeat(size);
            round(new Direct(report(),sig,intent()));round(new Relayed(report(),sig,intent(),sig));
            round(new Risk(RiskAction.LIQUIDATE,report(),sig));
        }
        assertThrows(IllegalArgumentException.class,()->new Direct(report(),"0x"+"ab".repeat(513),intent()));
        assertThrows(IllegalArgumentException.class,()->new Direct(report(),"0x1",intent()));
    }
    @Test void dynamicOffsetsMustBeCanonicalAlignedOutsideHeadAndNonoverlapping() {
        byte[] direct=codec.encodeBytes(direct());
        for(BigInteger offset:List.of(BigInteger.ZERO,n(18*32),n(41*32+1),n(42*32),UINT_MAX))reject(Target.CONTROLLER,word(direct,18,offset));
        byte[] relay=codec.encodeBytes(relayed());
        reject(Target.CONTROLLER,word(relay,41,n(42*32))); // second tail aliases first
        reject(Target.CONTROLLER,word(relay,41,n(45*32))); // unnecessary gap
        byte[] swapped=word(word(relay,18,n(44*32)),41,n(42*32));reject(Target.CONTROLLER,swapped);
    }
    @Test void dynamicLengthsOverLimitOrAvailablePayloadAreRejected() {
        byte[] direct=codec.encodeBytes(direct());
        for(BigInteger size:List.of(n(513),UINT_MAX,n(64)))reject(Target.CONTROLLER,word(direct,41,size));
        byte[] relay=codec.encodeBytes(relayed());reject(Target.CONTROLLER,word(relay,44,n(513)));
    }
    @Test void dynamicPaddingMustBeZeroForEveryTail() {
        byte[] direct=codec.encodeBytes(direct());direct[4+41*32+32+5]=1;reject(Target.CONTROLLER,direct);
        byte[] relay=codec.encodeBytes(relayed());relay[relay.length-1]=1;reject(Target.CONTROLLER,relay);
    }
    @Test void unknownSelectorWrongTargetAndUnsupportedPrivilegedFunctionsAreRejected() {
        reject(Target.TOKEN,codec.encodeBytes(direct()));reject(Target.VAULT,codec.encodeBytes(new Transfer(a(1),n(1))));
        reject(Target.CONTROLLER,HexFormat.of().parseHex("deadbeef"));
        // mint(address,uint256) is not public on CMockCollateral and is not whitelisted.
        reject(Target.TOKEN,HexFormat.of().parseHex("40c10f19"));
        assertThrows(IllegalArgumentException.class,()->codec.decode(null,codec.encode(direct())));
    }
    @Test void trailingWordsUnalignedPayloadAndEveryFixedCallTruncationAreRejected() {
        byte[] data=codec.encodeBytes(new TransferFrom(a(1),a(2),n(3)));
        for(int length=0;length<data.length;length++)reject(Target.TOKEN,Arrays.copyOf(data,length));
        reject(Target.TOKEN,Arrays.copyOf(data,data.length+32));reject(Target.TOKEN,Arrays.copyOf(data,data.length+1));
        byte[] direct=codec.encodeBytes(direct());reject(Target.CONTROLLER,Arrays.copyOf(direct,direct.length+32));
        for(int length:new int[]{4,36,4+40*32,4+41*32,4+42*32})reject(Target.CONTROLLER,Arrays.copyOf(direct,length));
    }
    @Test void payloadBoundAndInvalidHexAreRejectedWithoutProviderAccess() {
        reject(Target.CONTROLLER,new byte[CAbiCodec.MAX_CALLDATA_BYTES+1]);
        for(String invalid:List.of("", "0x1", "0xgg", "0X12345678", " 0x12345678", "0x"+"00".repeat(CAbiCodec.MAX_CALLDATA_BYTES+1)))
            assertThrows(IllegalArgumentException.class,()->codec.decode(Target.CONTROLLER,invalid));
    }
    @Test void syntaxRoundTripDoesNotInventAuthorizationSignatureOrFinancialValidation() {
        Direct unsigned=new Direct(report(),"0x",intent());round(unsigned);
        // The fixture intentionally has mismatched Intent/Report economic terms and an empty price signature.
        assertNotEquals(unsigned.intent().operationId(),unsigned.report().operationId());
        assertEquals("0x",((Direct)codec.decode(Target.CONTROLLER,codec.encode(unsigned))).priceSignature());
    }
    @Test void modelInputsAreImmutableAndHexLetterCaseIsOnlyTextualNormalization() {
        Transfer lower=new Transfer(a(0xab),n(1));Transfer upper=new Transfer(a(0xab).toUpperCase(Locale.ROOT).replace("0X","0x"),n(1));
        assertEquals(lower,upper);assertEquals(lower,codec.decode(Target.TOKEN,codec.encode(lower).toUpperCase(Locale.ROOT).replace("0X","0x")));
        byte[] first=codec.encodeBytes(lower);first[0]=0;
        assertEquals("0xa9059cbb",codec.encode(lower).substring(0,10));
    }
}
