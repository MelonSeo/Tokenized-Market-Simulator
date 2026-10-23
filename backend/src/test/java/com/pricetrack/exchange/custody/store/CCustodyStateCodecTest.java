package com.pricetrack.exchange.custody.store;

import static com.pricetrack.exchange.custody.model.CCustodyModels.*;
import static org.junit.jupiter.api.Assertions.*;
import com.pricetrack.exchange.custody.projection.CCustodyReducer;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;

class CCustodyStateCodecTest {
    static String a(int n) { return "0x"+String.format("%040x",n); }
    static String h(int n) { return "0x"+String.format("%064x",n); }
    static BigInteger n(long n) { return BigInteger.valueOf(n); }
    final Deployment d=new Deployment("C-codec",n(31337),h(100),a(10),a(11),a(12),a(13),a(14),a(15),a(16),h(200));
    final Block anchor=new Block(0,h(100),h(0)),block=new Block(1,h(101),h(100));
    final CCustodyReducer reducer=new CCustodyReducer(new CCustodyReducer.Limits(100,32,10,10,10));
    final CCustodyStateCodec codec=new CCustodyStateCodec(reducer);
    Bundle bundle(Call call,List<Log> logs) { return new Bundle(d,block,h(1001),0,a(1),a(11),Outcome.SUCCESS,call,logs); }
    State initial() { return reducer.initial(d,anchor); }
    int mapCountOffset() { return 9+codec.encodeDeployment(d).length-9+148+4; }
    int callOffset() { return 9+codec.encodeDeployment(d).length-9+148+70+4+46+46+1; }
    byte[] changed(byte[] source,int offset,int value) { byte[] b=source.clone();b[offset]=(byte)value;return b; }
    void rejectBundle(byte[] bytes) { assertThrows(IllegalArgumentException.class,()->codec.decodeBundle(bytes)); }

    @Test void deploymentHasVersionedDeterministicRoundTripAndStableDigest() {
        byte[] first=codec.encodeDeployment(d); assertEquals(d,codec.decodeDeployment(first));
        assertArrayEquals(first,codec.encodeDeployment(d));
        assertEquals(CCustodyStateCodec.sha256(first),CCustodyStateCodec.sha256(codec.encodeDeployment(codec.decodeDeployment(first))));
        assertTrue(CCustodyStateCodec.sha256(first).matches("[0-9a-f]{64}"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",CCustodyStateCodec.sha256(new byte[0]));
    }
    @Test void allWhitelistedCallTypesAndEnumValuesRoundTrip() {
        List<Call> calls=new ArrayList<>();
        for(VaultAction action:VaultAction.values()) calls.add(new VaultCall(action,a(1),BigInteger.ONE));
        for(UserAction action:UserAction.values()) for(boolean direct:List.of(false,true)) calls.add(new UserCall(action,a(1),h(1),h(2),n(3),n(4),n(5),n(6),h(7),h(8),direct));
        for(RiskAction action:RiskAction.values()) calls.add(new RiskCall(action,a(1),h(1),h(2),n(4),n(5),h(8)));
        calls.add(new CancelCall(a(1),UINT_MAX));calls.add(new PauseCall(false));calls.add(new PauseCall(true));
        calls.add(new ProcessClaimCall());calls.add(new DonationCall(BigInteger.ONE));
        for(Call call:calls) { Bundle b=bundle(call,List.of());assertEquals(b,codec.decodeBundle(codec.encodeBundle(b))); }
    }
    @Test void allWhitelistedEventTypesAndSignedAmountsRoundTrip() {
        List<Event> events=List.of(new Transfer(a(1),a(11),UINT_MAX),new Deposited(a(1),BigInteger.ONE),
            new Withdrawn(a(1),n(2)),new HouseFunded(a(3),n(4)),new Locked(h(1),h(2),a(1),n(5),n(6)),
            new RuleApplied(h(1),h(2),"LIQUIDATE",n(7),n(8),n(-8),n(9),n(10),n(11)),
            new ReportConsumed(h(1),n(1000),n(1030)),
            new UserExecuted(h(1),h(2),a(1),h(3),h(4),"OPEN",n(5),true),
            new RiskExecuted(h(1),h(2),a(1),h(4),"START_CALL"),
            new PositionSettled(h(1),h(2),a(1),n(8),n(-8),n(9),ZERO,ZERO),
            new ClaimCreated(n(1),a(1),h(2),h(1),n(10)),new ClaimPaid(n(1),a(1),n(2),n(8)),
            new NonceCancelled(a(1),n(12)),new PauseChanged(true));
        List<Log> logs=new ArrayList<>();for(int k=0;k<events.size();k++) logs.add(new Log(k,a(11),events.get(k)));
        Bundle b=bundle(new ProcessClaimCall(),logs);assertEquals(b,codec.decodeBundle(codec.encodeBundle(b)));
    }
    @Test void signedInt256ExtremesAndUnsignedMaximumArePreservedExactly() {
        for(BigInteger pnl:List.of(BigInteger.ONE.shiftLeft(255).negate(),BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE),n(-1),ZERO)) {
            Bundle b=bundle(new DonationCall(UINT_MAX),List.of(new Log(0,a(13),new RuleApplied(h(1),h(2),"CLOSE",UINT_MAX,UINT_MAX,pnl,UINT_MAX,UINT_MAX,UINT_MAX))));
            assertEquals(b,codec.decodeBundle(codec.encodeBundle(b)));
        }
    }
    @Test void fullProjectionWithObservationCanResumeWithoutLosingReplayIdentity() {
        Bundle deposit=bundle(new VaultCall(VaultAction.DEPOSIT,a(1),n(100)),List.of(
            new Log(0,a(10),new Transfer(a(1),a(11),n(100))),new Log(1,a(11),new Deposited(a(1),n(100)))));
        State s=reducer.apply(initial(),deposit);State restored=codec.decodeState(codec.encodeState(s));
        assertEquals(s,restored);assertSame(restored,reducer.apply(restored,deposit));assertFalse(restored.completeCoverage());
    }
    @Test void statePreservesClaimsNoncesOperationsAndEveryPositionStatus() {
        State claims=new State(d,anchor,-1,Map.of(a(1),new Balance(n(120),ZERO)),
            Map.of(h(2),new Position(a(1),ZERO,n(2),ZERO,ZERO,Status.CLOSED)),
            Map.of(n(1),new Claim(a(1),h(3),h(2),n(100),n(20),n(80))),Map.of(a(1),n(2)),Map.of(h(3),h(2)),
            n(120),ZERO,ZERO,n(120),n(80),n(1),n(2),true,Map.of());
        assertEquals(claims,codec.decodeState(codec.encodeState(claims)));
        for(Status status:Status.values()) {
            boolean active=status==Status.ACTIVE||status==Status.MARGIN_CALL;
            BigInteger locked=active?n(1):ZERO,deadline=status==Status.MARGIN_CALL?n(1060):ZERO;
            State state=new State(d,anchor,-1,Map.of(a(1),new Balance(ZERO,locked)),
                Map.of(h(2),new Position(a(1),locked,n(1),ZERO,deadline,status)),Map.of(),Map.of(a(1),n(1)),Map.of(h(3),h(2)),
                ZERO,locked,ZERO,locked,ZERO,n(1),n(1),false,Map.of());
            assertEquals(state,codec.decodeState(codec.encodeState(state)));
        }
    }
    State balances(Map<String,Balance> balances) {
        return new State(d,anchor,-1,balances,Map.of(),Map.of(),Map.of(),Map.of(),n(200),ZERO,ZERO,n(200),ZERO,n(1),n(1),false,Map.of());
    }
    @Test void mapInsertionOrderDoesNotAffectEncodedStateOrHash() {
        Map<String,Balance> ab=new LinkedHashMap<>(),ba=new LinkedHashMap<>();
        ab.put(a(1),new Balance(n(100),ZERO));ab.put(a(2),new Balance(n(100),ZERO));
        ba.put(a(2),new Balance(n(100),ZERO));ba.put(a(1),new Balance(n(100),ZERO));
        byte[] first=codec.encodeState(balances(ab)),second=codec.encodeState(balances(ba));
        assertArrayEquals(first,second);assertEquals(CCustodyStateCodec.sha256(first),CCustodyStateCodec.sha256(second));
    }
    @Test void unknownVersionMagicOrWrongObjectKindAreRejected() {
        byte[] encoded=codec.encodeDeployment(d);
        assertThrows(IllegalArgumentException.class,()->codec.decodeDeployment(changed(encoded,7,2)));
        assertThrows(IllegalArgumentException.class,()->codec.decodeDeployment(changed(encoded,0,0)));
        assertThrows(IllegalArgumentException.class,()->codec.decodeState(encoded));
    }
    @Test void allTruncationsAndTrailingBytesAreRejected() {
        byte[] encoded=codec.encodeDeployment(d);
        for(int n=0;n<encoded.length;n++) {
            byte[] truncated=Arrays.copyOf(encoded,n);
            assertThrows(IllegalArgumentException.class,()->codec.decodeDeployment(truncated));
        }
        byte[] trailing=Arrays.copyOf(encoded,encoded.length+1);
        assertThrows(IllegalArgumentException.class,()->codec.decodeDeployment(trailing));
    }
    @Test void oversizedPayloadAndStringsAreRejectedBeforeUnboundedAllocation() {
        assertThrows(IllegalArgumentException.class,()->codec.decodeState(new byte[CCustodyStateCodec.MAX_PAYLOAD+1]));
        Deployment huge=new Deployment("x".repeat(CCustodyStateCodec.MAX_STRING+1),n(31337),h(100),a(10),a(11),a(12),a(13),a(14),a(15),a(16),h(200));
        assertThrows(IllegalArgumentException.class,()->codec.encodeDeployment(huge));
        byte[] encoded=codec.encodeDeployment(d);ByteBuffer.wrap(encoded).putInt(9,Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,()->codec.decodeDeployment(encoded));
    }
    @Test void unknownCallEventAndEnumTagsAreRejected() {
        byte[] encoded=codec.encodeBundle(bundle(new ProcessClaimCall(),List.of()));
        rejectBundle(changed(encoded,callOffset(),255));
        encoded=codec.encodeBundle(bundle(new VaultCall(VaultAction.DEPOSIT,a(1),n(1)),List.of()));
        rejectBundle(changed(encoded,callOffset()+1,0));
        encoded=codec.encodeBundle(bundle(new ProcessClaimCall(),List.of(new Log(0,a(11),new PauseChanged(false)))));
        int eventOffset=callOffset()+1+4+4+46;
        rejectBundle(changed(encoded,eventOffset,255));
    }
    @Test void strictUtf8RejectsMalformedByteAndUnpairedSurrogate() {
        byte[] encoded=codec.encodeDeployment(d);ByteBuffer.wrap(encoded).putInt(9,1);encoded[13]=(byte)0x80;
        assertThrows(IllegalArgumentException.class,()->codec.decodeDeployment(encoded));
        Deployment surrogate=new Deployment("bad\ud800",n(31337),h(100),a(10),a(11),a(12),a(13),a(14),a(15),a(16),h(200));
        assertThrows(IllegalArgumentException.class,()->codec.encodeDeployment(surrogate));
    }
    @Test void duplicateMapKeysAndNoncanonicalMapOrderAreRejected() {
        State s=balances(Map.of(a(1),new Balance(n(100),ZERO),a(2),new Balance(n(100),ZERO)));
        byte[] encoded=codec.encodeState(s);int first=mapCountOffset()+4,second=first+110;
        byte[] duplicate=encoded.clone();System.arraycopy(duplicate,first,duplicate,second,46);
        assertThrows(IllegalArgumentException.class,()->codec.decodeState(duplicate));
        byte[] reordered=encoded.clone();System.arraycopy(encoded,second,reordered,first,110);System.arraycopy(encoded,first,reordered,second,110);
        assertThrows(IllegalArgumentException.class,()->codec.decodeState(reordered));
    }
    @Test void negativeOrExcessiveCollectionCountsAreRejected() {
        byte[] initial=codec.encodeState(initial());
        byte[] negative=initial.clone();ByteBuffer.wrap(negative).putInt(mapCountOffset(),-1);
        assertThrows(IllegalArgumentException.class,()->codec.decodeState(negative));
        byte[] excessive=initial.clone();ByteBuffer.wrap(excessive).putInt(mapCountOffset(),CCustodyStateCodec.MAX_ENTRIES+1);
        assertThrows(IllegalArgumentException.class,()->codec.decodeState(excessive));
        Bundle huge=bundle(new ProcessClaimCall(),Collections.nCopies(CCustodyStateCodec.MAX_ENTRIES+1,new Log(0,a(11),new PauseChanged(false))));
        assertThrows(IllegalArgumentException.class,()->codec.encodeBundle(huge));
    }
    @Test void fixedWidthIntegersRejectOverflowNegativeUnsignedAndExtraSignBytes() {
        for(BigInteger invalid:List.of(n(-1),UINT_MAX.add(BigInteger.ONE))) {
            assertThrows(IllegalArgumentException.class,()->codec.encodeBundle(bundle(new DonationCall(invalid),List.of())));
        }
        Bundle signedTooLarge=bundle(new ProcessClaimCall(),List.of(new Log(0,a(13),new RuleApplied(h(1),h(2),"CLOSE",n(1),n(1),BigInteger.ONE.shiftLeft(255),ZERO,ZERO,ZERO))));
        assertThrows(IllegalArgumentException.class,()->codec.encodeBundle(signedTooLarge));
        byte[] encoded=codec.encodeBundle(bundle(new DonationCall(BigInteger.ONE),List.of()));
        int amount=callOffset()+1;byte[] noncanonical=new byte[encoded.length+1];
        System.arraycopy(encoded,0,noncanonical,0,amount);System.arraycopy(encoded,amount,noncanonical,amount+1,encoded.length-amount);
        rejectBundle(noncanonical);
    }
    @Test void booleanHasOnlyZeroAndOneEncoding() {
        byte[] encoded=codec.encodeBundle(bundle(new PauseCall(true),List.of()));
        rejectBundle(changed(encoded,callOffset()+1,2));
    }
    @Test void decodedStateMustSatisfyReducerInvariantsNotMerelyBinaryShape() {
        byte[] encoded=codec.encodeState(initial());
        int totalFree=mapCountOffset()+5*4;encoded[totalFree+31]=1;
        assertThrows(IllegalArgumentException.class,()->codec.decodeState(encoded));
    }
    @Test void noncanonicalAddressesAndUnknownActionStringsCannotBeEncoded() {
        Bundle mixed=bundle(new VaultCall(VaultAction.DEPOSIT,"0x"+"A".repeat(40),n(1)),List.of());
        assertThrows(IllegalArgumentException.class,()->codec.encodeBundle(mixed));
        Bundle unknown=bundle(new ProcessClaimCall(),List.of(new Log(0,a(13),new RuleApplied(h(1),h(2),"ADMIN_SWEEP",n(1),n(1),ZERO,ZERO,ZERO,ZERO))));
        assertThrows(IllegalArgumentException.class,()->codec.encodeBundle(unknown));
    }
    @Test void malformedObservationKeysCannotSurviveStateDecodeOrEncode() {
        Bundle b=bundle(new ProcessClaimCall(),List.of());
        State bad=new State(d,block,0,Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),ZERO,ZERO,ZERO,ZERO,ZERO,n(1),n(1),false,
            Map.of(new TxKey(h(999),b.transactionHash()),b));
        assertThrows(IllegalArgumentException.class,()->codec.encodeState(bad));
    }
}
