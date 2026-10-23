package com.pricetrack.exchange.custody.abi;

import static com.pricetrack.exchange.custody.abi.CAbiEventModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Independent local cast topic/data goldens plus malformed-word tests, not merely codec roundtrips. */
public class CAbiEventCodecTest {
    private final CAbiEventCodec codec=new CAbiEventCodec();
    private static final String EMITTER="0x0000000000000000000000000000000000000064";
    private static final String OWNER="0x0000000000000000000000000000000000000003";
    private static final String ZERO_ADDRESS="0x"+"0".repeat(40);
    private static final String H0="0x"+"0".repeat(64);
    private static final String H1="0x0000000000000000000000000000000000000000000000000000000000000001";
    private static final String H2="0x0000000000000000000000000000000000000000000000000000000000000002";
    private static final String H3="0x0000000000000000000000000000000000000000000000000000000000000003";
    private static final String H6="0x0000000000000000000000000000000000000000000000000000000000000006";
    private static final String H7="0x0000000000000000000000000000000000000000000000000000000000000007";
    // cast abi-encode f(uint256,int256,uint256,uint256,uint256) 1000 -100 1000 25 3
    private static final String SETTLED_DATA="0x00000000000000000000000000000000000000000000000000000000000003e8"
        +"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff9c"
        +"00000000000000000000000000000000000000000000000000000000000003e8"
        +"0000000000000000000000000000000000000000000000000000000000000019"
        +"0000000000000000000000000000000000000000000000000000000000000003";
    // cast abi-encode f(uint8,uint256,uint256,uint256,int256,int256,uint256,uint256,uint256) 5 2 8000000000 1000 -2000 -1000 1000 1 1060
    private static final String OP_DATA="0x0000000000000000000000000000000000000000000000000000000000000005"
        +"0000000000000000000000000000000000000000000000000000000000000002"
        +"00000000000000000000000000000000000000000000000000000001dcd65000"
        +"00000000000000000000000000000000000000000000000000000000000003e8"
        +"fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff830"
        +"fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffc18"
        +"00000000000000000000000000000000000000000000000000000000000003e8"
        +"0000000000000000000000000000000000000000000000000000000000000001"
        +"0000000000000000000000000000000000000000000000000000000000000424";
    // cast abi-encode f(bytes32,bytes32,uint8,uint256,bool) <H6> <H7> 4 9 true
    private static final String USER_DATA=H6+H7.substring(2)
        +"0000000000000000000000000000000000000000000000000000000000000004"
        +"0000000000000000000000000000000000000000000000000000000000000009"
        +"0000000000000000000000000000000000000000000000000000000000000001";

    @Test void independentCastPositionSettlementPreservesAllFiveDataFieldsAndSignedLoss() {
        check(new PositionSettled(H1,H2,OWNER,bi(1000),bi(-100),bi(1000),bi(25),bi(3)),
            "0xf0d0093595ccb214443df0640d9b24560ffe0a1b34118fabc9b7bcfc425ca909",List.of(H1,H2,H3),SETTLED_DATA);
        // This intentionally inconsistent badDebt/PnL combination is ABI syntax, not approved economics.
    }
    @Test void independentCastOperationAppliedPreservesPriceUnrealizedAndEveryRuleField() {
        check(new OperationApplied(H1,H2,bi(5),bi(2),bi(8_000_000_000L),bi(1000),bi(-2000),bi(-1000),bi(1000),bi(1),bi(1060)),
            "0x5997542be234c7715122caa3d3458cb56eccb19559f19caa0b299736a2215fb7",List.of(H1,H2),OP_DATA);
    }
    @Test void independentCastUserIntentExecutedKeepsBothDigestsActionNonceDirect() {
        check(new CUserIntentExecuted(H1,H2,OWNER,H6,H7,bi(4),bi(9),true),
            "0xbde67fc07d3d2ad531b5d828d3b3d01d60cb2b2c3645e11729ba41d362234c76",List.of(H1,H2,H3),USER_DATA);
    }
    @Test void allSixteenTopicZeroConstantsMatchIndependentCastKeccakGoldens() {
        String[] expected={
            "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925",
            "2da466a7b24304f47e87fa2e1e5a81b9831ce54fec19055ce277ca2f39ba42c4","7084f5476618d8e60b11ef0d7d3f06914655adb8793e28ff7f018d4c76d505d5",
            "68b5652e3b29601ecd57e94e383b10ba5e2f2726963da06839bb31da7e5dd0d9","ffe4f8c1183cc43d5389463431ce739b36c911ab0809cc2bb8bbf2b71f639ff0",
            "f0d0093595ccb214443df0640d9b24560ffe0a1b34118fabc9b7bcfc425ca909","fdf4d26e970338cb75a69697dcd0876a1279669b1df41a6da01e2226ad327187",
            "ea87e8a9c18a06cd8bff5490efeb29080edd7ae21403c90d370da474d6353c73","bde67fc07d3d2ad531b5d828d3b3d01d60cb2b2c3645e11729ba41d362234c76",
            "4260c65c4e668ba8f33b7c425c01f6603b24ffcae2394bff67a46107345e8c43","7b644e7863e42132ae13a74c64f69b27bbc894b15f690a353b2aad750c7c334a",
            "0a4198dd4f22e2bab3c2cf34b8a31f3d2de46956a4b7f75c66b577f5977c4bc3","5997542be234c7715122caa3d3458cb56eccb19559f19caa0b299736a2215fb7",
            "53658988139a5c832ed50acd661beaa87482d2cc105cb640afc2c34dde4636c0","0a6387c9ea3628b88a633bb4f3b151770f70085117a15f9bf3787cda53f13d31"};
        assertEquals(16,CAbiEventCodec.Type.values().length);
        for(int n=0;n<expected.length;n++)assertEquals("0x"+expected[n],CAbiEventCodec.Type.values()[n].topic0);
    }
    @Test void tokenTransferZeroMintSyntaxAndMaximumValueArePreservedNotAuthorized() {
        check(new Transfer(ZERO_ADDRESS,OWNER,UINT_MAX),"0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
            List.of(H0,H3),"0x"+"f".repeat(64));
    }
    @Test void approvalZeroOwnerAndSpenderRemainValidSyntaxOnly() {
        check(new Approval(ZERO_ADDRESS,ZERO_ADDRESS,bi(1)),"0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925",List.of(H0,H0),words(1));
    }
    @Test void simpleVaultEventGoldenLayouts() {
        check(new Deposited(OWNER,bi(1000)),"0x2da466a7b24304f47e87fa2e1e5a81b9831ce54fec19055ce277ca2f39ba42c4",List.of(H3),words(1000));
        check(new Withdrawn(OWNER,bi(1000)),"0x7084f5476618d8e60b11ef0d7d3f06914655adb8793e28ff7f018d4c76d505d5",List.of(H3),words(1000));
        check(new HouseFunded(OWNER,bi(1000)),"0x68b5652e3b29601ecd57e94e383b10ba5e2f2726963da06839bb31da7e5dd0d9",List.of(H3),words(1000));
    }
    @Test void lockedEventKeepsIndexedIdsOwnerAndBothAmounts() {
        check(new CollateralLocked(H1,H2,OWNER,bi(25),bi(1000)),"0xffe4f8c1183cc43d5389463431ce739b36c911ab0809cc2bb8bbf2b71f639ff0",List.of(H1,H2,H3),words(25,1000));
    }
    @Test void claimCreationSeparatesIndexedClaimOwnerPositionFromDataOperation() {
        check(new ProfitClaimCreated(bi(1),OWNER,H2,H6,bi(950)),"0xfdf4d26e970338cb75a69697dcd0876a1279669b1df41a6da01e2226ad327187",List.of(H1,H3,H2),H6+words(950).substring(2));
    }
    @Test void claimPaymentPreservesRemainingAndClaimId() {
        check(new ProfitClaimPaid(bi(1),OWNER,bi(25),bi(925)),"0xea87e8a9c18a06cd8bff5490efeb29080edd7ae21403c90d370da474d6353c73",List.of(H1,H3),words(25,925));
    }
    @Test void riskEventRetainsFullReportDigestAndUint8Action() {
        check(new CRiskExecuted(H1,H2,OWNER,H7,bi(5)),"0x4260c65c4e668ba8f33b7c425c01f6603b24ffcae2394bff67a46107345e8c43",List.of(H1,H2,H3),H7+words(5).substring(2));
    }
    @Test void nonceEventDoesNotNarrowUint256ToLong() {
        check(new CNonceCancelled(OWNER,UINT_MAX),"0x7b644e7863e42132ae13a74c64f69b27bbc894b15f690a353b2aad750c7c334a",List.of(H3),"0x"+"f".repeat(64));
    }
    @Test void pauseBoolTrueAndFalseGoldenWords() {
        check(new NewPositionsPauseChanged(true),"0x0a4198dd4f22e2bab3c2cf34b8a31f3d2de46956a4b7f75c66b577f5977c4bc3",List.of(),words(1));
        check(new NewPositionsPauseChanged(false),"0x0a4198dd4f22e2bab3c2cf34b8a31f3d2de46956a4b7f75c66b577f5977c4bc3",List.of(),words(0));
    }
    @Test void reportConsumedKeepsBothTimesWithoutClaimingTtlVerified() {
        check(new FuturesReportConsumed(H1,bi(1000),bi(999)),"0x53658988139a5c832ed50acd661beaa87482d2cc105cb640afc2c34dde4636c0",List.of(H1),words(1000,999));
    }
    @Test void allEmitterKindsMustMatchExpectedCategoryButAddressesAreNotAuthenticated() {
        for(Event event:samples())for(EmitterKind kind:EmitterKind.values()) {
            Log log=codec.encode(EMITTER,event);
            if(CAbiEventCodec.type(event).accepts(kind))assertEquals(event,codec.decode(kind,log));
            else assertThrows(IllegalArgumentException.class,()->codec.decode(kind,log));
        }
        Log differentEmitter=codec.encode("0x"+"a".repeat(40),new Deposited(OWNER,bi(1)));
        assertEquals(new Deposited(OWNER,bi(1)),codec.decode(EmitterKind.VAULT,differentEmitter));
    }
    @Test void unknownOrNonExactTopicZeroIsRejected() {
        Log known=codec.encode(EMITTER,new Deposited(OWNER,bi(1)));
        assertThrows(IllegalArgumentException.class,()->codec.decode(EmitterKind.VAULT,new Log(EMITTER,List.of(H0,H3),known.data())));
        assertThrows(IllegalArgumentException.class,()->new Log(EMITTER,List.of(known.topics().getFirst()+"00",H3),known.data()));
    }
    @Test void everyEventRejectsMissingExtraTopicsAndTruncatedOrTrailingData() {
        for(Event event:samples()) {
            Log log=codec.encode(EMITTER,event); EmitterKind kind=CAbiEventCodec.type(event).emitterKind;
            List<String> fewer=new ArrayList<>(log.topics());fewer.removeLast();
            assertThrows(IllegalArgumentException.class,()->codec.decode(kind,new Log(EMITTER,fewer,log.data())));
            List<String> extra=new ArrayList<>(log.topics());extra.add(H0);
            assertThrows(IllegalArgumentException.class,()->codec.decode(kind,new Log(EMITTER,extra,log.data())));
            if(!log.data().equals("0x"))assertThrows(IllegalArgumentException.class,()->codec.decode(kind,new Log(EMITTER,log.topics(),log.data().substring(0,log.data().length()-2))));
            assertThrows(IllegalArgumentException.class,()->codec.decode(kind,new Log(EMITTER,log.topics(),log.data()+"00".repeat(32))));
        }
    }
    @Test void indexedAddressHighPaddingCannotBeTruncatedAway() {
        Log good=codec.encode(EMITTER,new Transfer(OWNER,ZERO_ADDRESS,bi(1)));
        String dirty="0x01"+H3.substring(4);
        assertThrows(IllegalArgumentException.class,()->codec.decode(EmitterKind.TOKEN,new Log(EMITTER,List.of(good.topics().getFirst(),dirty,H0),good.data())));
    }
    @Test void uint8RejectsDirtyHighBitsInsteadOfMasking() {
        Event[] events={new CRiskExecuted(H1,H2,OWNER,H7,bi(5)),new CUserIntentExecuted(H1,H2,OWNER,H6,H7,bi(4),bi(9),true),
            new OperationApplied(H1,H2,bi(5),bi(2),bi(1),bi(1),bi(-1),bi(-1),bi(0),bi(1),bi(60))};
        int[] word={1,2,0};
        for(int n=0;n<events.length;n++) {
            Log log=codec.encode(EMITTER,events[n]);String dirty=replace(log.data(),word[n],word(bi(256)));
            EmitterKind kind=CAbiEventCodec.type(events[n]).emitterKind;
            assertThrows(IllegalArgumentException.class,()->codec.decode(kind,new Log(EMITTER,log.topics(),dirty)));
        }
    }
    @Test void boolRejectsAllValuesOtherThanZeroAndOne() {
        for(BigInteger bad:List.of(bi(2),UINT_MAX)) {
            Log pause=codec.encode(EMITTER,new NewPositionsPauseChanged(true));
            assertThrows(IllegalArgumentException.class,()->codec.decode(EmitterKind.CONTROLLER,new Log(EMITTER,pause.topics(),word(bad))));
            Log user=codec.encode(EMITTER,new CUserIntentExecuted(H1,H2,OWNER,H6,H7,bi(4),bi(9),true));
            assertThrows(IllegalArgumentException.class,()->codec.decode(EmitterKind.CONTROLLER,new Log(EMITTER,user.topics(),replace(user.data(),4,word(bad)))));
        }
    }
    @Test void signedExtremesAndModelRangesAreExact() {
        for(BigInteger value:List.of(INT_MIN,INT_MAX,bi(-1),BigInteger.ZERO)) {
            PositionSettled event=new PositionSettled(H1,H2,OWNER,UINT_MAX,value,UINT_MAX,UINT_MAX,UINT_MAX);
            Log log=codec.encode(EMITTER,event); assertEquals(value,((PositionSettled)codec.decode(EmitterKind.VAULT,log)).realizedPnl());
            assertEquals(value.testBit(255)?value.add(BigInteger.ONE.shiftLeft(256)):value,new BigInteger(log.data().substring(66,130),16));
        }
        assertThrows(IllegalArgumentException.class,()->new PositionSettled(H1,H2,OWNER,bi(1),INT_MIN.subtract(bi(1)),bi(0),bi(0),bi(0)));
        assertThrows(IllegalArgumentException.class,()->new PositionSettled(H1,H2,OWNER,bi(1),INT_MAX.add(bi(1)),bi(0),bi(0),bi(0)));
        assertThrows(IllegalArgumentException.class,()->new Deposited(OWNER,bi(-1)));
        assertThrows(IllegalArgumentException.class,()->new Deposited(OWNER,UINT_MAX.add(bi(1))));
    }
    @Test void invalidHexNullsAndDynamicOffsetTailsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new Log("0x1",List.of(H1),words(1)));
        assertThrows(IllegalArgumentException.class,()->new Log(EMITTER,List.of(H1),"0x1"));
        assertThrows(IllegalArgumentException.class,()->new Log(EMITTER,List.of(H1),"0xgg"));
        assertThrows(IllegalArgumentException.class,()->new Log(EMITTER,null,words(1)));
        assertThrows(IllegalArgumentException.class,()->codec.decode(null,codec.encode(EMITTER,new Deposited(OWNER,bi(1)))));
        assertThrows(IllegalArgumentException.class,()->codec.encode(EMITTER,null));
        Log log=codec.encode(EMITTER,new Deposited(OWNER,bi(1)));
        // Dynamic uint[] ABI offset+length+tail cannot masquerade as this static uint256 event.
        assertThrows(IllegalArgumentException.class,()->codec.decode(EmitterKind.VAULT,new Log(EMITTER,log.topics(),words(32,1,1))));
    }
    @Test void topicsAreDefensivelyCopiedAndHexCaseCanonicalized() {
        Log original=codec.encode(EMITTER,new Deposited(OWNER,bi(1)));
        List<String> mutable=new ArrayList<>(original.topics());
        Log log=new Log(EMITTER,mutable,"0x"+original.data().substring(2).toUpperCase(Locale.ROOT));
        mutable.set(0,H0);assertEquals(original,log);assertThrows(UnsupportedOperationException.class,()->log.topics().set(0,H0));
    }
    @Test void seededSignedIntegerDataIsLosslessAcrossFullWidth() {
        Random random=new Random(0xC4C1B);
        for(int n=0;n<100;n++) {
            byte[] raw=new byte[32];random.nextBytes(raw);BigInteger value=new BigInteger(raw);
            PositionSettled event=new PositionSettled(H1,H2,OWNER,bi(1),value,bi(0),bi(0),bi(0));
            Log log=codec.encode(EMITTER,event);
            assertEquals(HexFormat.of().formatHex(raw),log.data().substring(66,130));
            assertEquals(event,codec.decode(EmitterKind.VAULT,log));
        }
    }
    @Test void inheritedDomainChangedIsSyntaxOnlyAtControllerOrRulesNotTokenOrVault() {
        check(new EIP712DomainChanged(),"0x0a6387c9ea3628b88a633bb4f3b151770f70085117a15f9bf3787cda53f13d31",List.of(),"0x");
        Log log=codec.encode(EMITTER,new EIP712DomainChanged());
        assertEquals(new EIP712DomainChanged(),codec.decode(EmitterKind.CONTROLLER,log));
        assertEquals(new EIP712DomainChanged(),codec.decode(EmitterKind.RULES,log));
        assertThrows(IllegalArgumentException.class,()->codec.decode(EmitterKind.TOKEN,log));
        assertThrows(IllegalArgumentException.class,()->codec.decode(EmitterKind.VAULT,log));
    }
    private void check(Event event,String topic,List<String> indexed,String data) {
        List<String> topics=new ArrayList<>();topics.add(topic);topics.addAll(indexed);Log expected=new Log(EMITTER,topics,data);
        assertEquals(expected,codec.encode(EMITTER,event));assertEquals(event,codec.decode(CAbiEventCodec.type(event).emitterKind,expected));
    }
    private static List<Event> samples() {return List.of(new Transfer(OWNER,OWNER,bi(1)),new Approval(OWNER,OWNER,bi(1)),
        new Deposited(OWNER,bi(1)),new Withdrawn(OWNER,bi(1)),new HouseFunded(OWNER,bi(1)),new CollateralLocked(H1,H2,OWNER,bi(1),bi(2)),
        new PositionSettled(H1,H2,OWNER,bi(1),bi(-1),bi(0),bi(0),bi(0)),new ProfitClaimCreated(bi(1),OWNER,H2,H1,bi(2)),
        new ProfitClaimPaid(bi(1),OWNER,bi(1),bi(1)),new CUserIntentExecuted(H1,H2,OWNER,H6,H7,bi(4),bi(1),true),
        new CRiskExecuted(H1,H2,OWNER,H7,bi(5)),new CNonceCancelled(OWNER,bi(1)),new NewPositionsPauseChanged(true),
        new OperationApplied(H1,H2,bi(5),bi(1),bi(1),bi(1),bi(-1),bi(-1),bi(0),bi(1),bi(60)),new FuturesReportConsumed(H1,bi(1000),bi(1030)),new EIP712DomainChanged());}
    private static BigInteger bi(long value) {return BigInteger.valueOf(value);}
    private static String word(BigInteger value) {return "0x"+String.format("%064x",value);}
    private static String words(long... values) {StringBuilder data=new StringBuilder("0x");for(long value:values)data.append(word(bi(value)).substring(2));return data.toString();}
    private static String replace(String data,int index,String word) {return data.substring(0,2+index*64)+word.substring(2)+data.substring(2+(index+1)*64);}
}
