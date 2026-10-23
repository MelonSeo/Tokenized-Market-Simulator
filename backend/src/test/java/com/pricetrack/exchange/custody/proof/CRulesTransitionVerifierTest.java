package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CRulesTransitionModels.*;
import static org.junit.jupiter.api.Assertions.*;
import com.pricetrack.exchange.custody.abi.CAbiModels.Report;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.OperationApplied;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.FuturesReportConsumed;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

/** C-specific offline state/math goldens, no signature/DB/RPC or real-time grace claim. */
public class CRulesTransitionVerifierTest {
    private final CRulesTransitionVerifier verifier=new CRulesTransitionVerifier();
    private static final BigInteger ZERO=BigInteger.ZERO,ONE=BigInteger.ONE;
    private static final BigInteger MAX=ONE.shiftLeft(256).subtract(ONE);
    private static final String POS=hash(1),ACCOUNT=hash(2),SYMBOL=hash(3),POLICY=hash(4);
    private static final String EXEC="0x0000000000000000000000000000000000000005";
    private static final Terms TERMS=new Terms(EXEC,SYMBOL,POLICY);

    @Test void bothSidesAndAllLeveragesMatchCQuantityGolden() {
        for(int side=0;side<=1;side++)for(int leverage:List.of(2,5,10)) {
            Report r=open(side,leverage,1000,10_000_000_000L,1000);
            Result result=verifier.verify(input(Action.OPEN,r,Position.empty(),1000));
            assertEquals(bi(10L*leverage),result.after().quantity());
            assertEquals(Status.ACTIVE,result.after().status());assertEquals(ONE,result.after().version());
            assertEquals(ZERO,result.operationApplied().unrealized());assertEquals(POS,result.activePositionForAccount());assertTrue(result.operationConsumed());
        }
    }
    @Test void longPositiveC3GoldenHasExactPnlEquityAndMaintenance() {
        Position p=active();Result result=run(Action.CLOSE,p,11_000_000_000L,1000,1000);
        assertEquals(bi(1000),result.mark().unrealized());assertEquals(bi(2000),result.mark().equity());
        assertEquals(bi(550),result.mark().maintenance());assertEquals(bi(1000),result.operationApplied().realized());
        assertEquals(ZERO,result.operationApplied().badDebt());
    }
    @Test void negativeC3GoldenCapsRealizedButPreservesUnrealizedAndBadDebt() {
        Position p=active();Result call=run(Action.START_CALL,p,8_000_000_000L,1000,1000);
        Result liq=run(Action.LIQUIDATE,call.after(),8_000_000_000L,1000,1060);
        assertEquals(bi(-2000),liq.mark().unrealized());assertEquals(bi(-1000),liq.mark().equity());
        assertEquals(bi(400),liq.mark().maintenance());assertEquals(bi(-1000),liq.operationApplied().realized());
        assertEquals(bi(1000),liq.operationApplied().badDebt());assertEquals(bi(3),liq.after().version());
    }
    @Test void negativeFloorAndMaintenanceCeilingHaveOneWeiGolden() {
        // C=2 wei,L=2,entry=1e8 -> q4; mark74999999 -> signed -100000004 /1e8 floors to -2.
        Report open=open(0,2,2,100_000_000,1000);
        Position p=verifier.verify(input(Action.OPEN,open,Position.empty(),1000)).after();
        Result result=run(Action.CLOSE,p,74_999_999,2,1000);
        assertEquals(bi(-2),result.mark().unrealized());assertEquals(ONE,result.mark().maintenance());
        assertEquals(ZERO,result.mark().equity());assertEquals(bi(-2),result.operationApplied().realized());
    }
    @Test void closeKeepsRulesTerminalCollateralAndClearsActivePointerOnly() {
        Position p=active();Result result=run(Action.CLOSE,p,10_000_000_000L,1000,1000);
        assertEquals(Status.CLOSED,result.after().status());assertEquals(bi(1000),result.after().collateral());
        assertEquals(p.quantity(),result.after().quantity());assertEquals(ZERO_HASH,result.activePositionForAccount());
        assertEquals(bi(1000),result.operationApplied().collateral());
    }
    @Test void topUpChangesCollateralButNotEntryQuantityOrCallEpoch() {
        Position p=active();Result result=run(Action.TOP_UP,p,10_100_000_000L,1100,1000);
        assertEquals(bi(1100),result.after().collateral());assertEquals(p.quantity(),result.after().quantity());
        assertEquals(p.entryPriceE8(),result.after().entryPriceE8());assertEquals(p.callEpoch(),result.after().callEpoch());
    }
    @Test void topUpRecoveryClearsDeadlineWithoutNewEpoch() {
        Position call=run(Action.START_CALL,active(),9_000_000_000L,1000,1000).after();
        Result result=run(Action.TOP_UP,call,9_000_000_000L,2000,1000);
        assertEquals(Status.ACTIVE,result.after().status());assertEquals(ZERO,result.after().deadline());
        assertEquals(ONE,result.after().callEpoch());assertEquals(ZERO,result.operationApplied().realized());
    }
    @Test void stillRiskyTopUpPreservesMarginCallAndItsDeadline() {
        Position call=run(Action.START_CALL,active(),8_000_000_000L,1000,1000).after();
        Result result=run(Action.TOP_UP,call,8_000_000_000L,1100,1000);
        assertEquals(Status.MARGIN_CALL,result.after().status());assertEquals(bi(1060),result.after().deadline());
        assertEquals(bi(-2000),result.operationApplied().unrealized());
        assertEquals(ZERO,result.operationApplied().realized());assertEquals(ZERO,result.operationApplied().badDebt());
    }
    @Test void startCallAddsExactlySixtySecondsAndOneEpoch() {
        Result result=run(Action.START_CALL,active(),9_000_000_000L,1000,1000);
        assertEquals(Status.MARGIN_CALL,result.after().status());assertEquals(ONE,result.after().callEpoch());
        assertEquals(bi(1060),result.after().deadline());assertTrue(result.mark().atRisk());
    }
    @Test void clearRecoveredCallPreservesEpochAndSetsDeadlineZero() {
        Position call=run(Action.START_CALL,active(),9_000_000_000L,1000,1000).after();
        Result result=run(Action.CLEAR_CALL,call,10_000_000_000L,1000,1000);
        assertEquals(Status.ACTIVE,result.after().status());assertEquals(ONE,result.after().callEpoch());
        assertEquals(ZERO,result.after().deadline());
    }
    @Test void startCallRejectsHealthyOrAlreadyCalledPosition() {
        assertThrows(IllegalArgumentException.class,()->run(Action.START_CALL,active(),10_000_000_000L,1000,1000));
        Position call=run(Action.START_CALL,active(),9_000_000_000L,1000,1000).after();
        assertThrows(IllegalArgumentException.class,()->run(Action.START_CALL,call,9_000_000_000L,1000,1000));
    }
    @Test void clearCallRejectsStillRiskyOrNotCalledPosition() {
        Position call=run(Action.START_CALL,active(),9_000_000_000L,1000,1000).after();
        assertThrows(IllegalArgumentException.class,()->run(Action.CLEAR_CALL,call,9_000_000_000L,1000,1000));
        assertThrows(IllegalArgumentException.class,()->run(Action.CLEAR_CALL,active(),10_000_000_000L,1000,1000));
    }
    @Test void fiftyNineSecondsFailsAndSixtyWithNewObservationSucceeds() {
        Position call=run(Action.START_CALL,active(),8_000_000_000L,1000,1000).after();
        assertThrows(IllegalArgumentException.class,()->run(Action.LIQUIDATE,call,8_000_000_000L,1000,1059));
        Result result=run(Action.LIQUIDATE,call,8_000_000_000L,1000,1060);
        assertEquals(Status.LIQUIDATED,result.after().status());assertEquals(bi(1060),result.after().lastObservedAt());
        assertEquals(bi(1060),result.after().deadline());assertEquals(ZERO_HASH,result.activePositionForAccount());
    }
    @Test void liquidateRejectsRecoveredPositionAtDeadline() {
        Position call=run(Action.START_CALL,active(),9_000_000_000L,1000,1000).after();
        assertThrows(IllegalArgumentException.class,()->run(Action.LIQUIDATE,call,10_000_000_000L,1000,1060));
    }
    @Test void liquidateRequiresObservedAtOnOrAfterCallDeadlineEvenIfReportTtlValid() {
        Position call=run(Action.START_CALL,active(),8_000_000_000L,1000,1000).after();
        Report old=report(Action.LIQUIDATE,call,8_000_000_000L,1000,1059);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.LIQUIDATE,old,call,1060)));
    }
    @Test void liquidateRejectsFutureObservationEvenInsideOrdinaryTwoSecondAllowance() {
        Position call=run(Action.START_CALL,active(),8_000_000_000L,1000,1000).after();
        Report future=report(Action.LIQUIDATE,call,8_000_000_000L,1000,1061);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.LIQUIDATE,future,call,1060)));
    }
    @Test void reportAgeThirtyInclusiveAndThirtyOneExpired() {
        Position p=active();Report r=report(Action.CLOSE,p,10_000_000_000L,1000,1000);
        assertNotNull(verifier.verify(input(Action.CLOSE,r,p,1030)));
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.CLOSE,r,p,1031)));
    }
    @Test void ordinaryFutureTwoInclusiveAndThreeRejected() {
        Position p=active();Report two=report(Action.CLOSE,p,10_000_000_000L,1000,1002);
        assertEquals(bi(1002),verifier.verify(input(Action.CLOSE,two,p,1000)).after().lastObservedAt());
        Report three=report(Action.CLOSE,p,10_000_000_000L,1000,1003);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.CLOSE,three,p,1000)));
    }
    @Test void reportObservationCannotRegressBehindFullPositionLastObservation() {
        Position p=change(active(),"lastObservedAt",bi(1001));Report r=report(Action.CLOSE,p,10_000_000_000L,1000,1000);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.CLOSE,r,p,1000)));
    }
    @Test void invalidTtlShapeObservedAdditionAndCurrentTimeAdditionCannotOverflow() {
        Position p=active();Report r=report(Action.CLOSE,p,10_000_000_000L,1000,1000);
        Report wrong=change(r,"validUntil",bi(1031));
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.CLOSE,wrong,p,1000)));
        Report overflow=change(change(r,"observedAt",MAX.subtract(bi(29))),"validUntil",MAX);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.CLOSE,overflow,p,MAX.subtract(bi(29)))));
        assertThrows(IllegalArgumentException.class,()->verifier.verify(new Input(Action.CLOSE,r,TERMS,p,MAX,false,POS,keys(r))));
    }
    @Test void zeroStrictLimitsAndUnsupportedLeverageAreRejected() {
        Report original=open(0,10,1000,10_000_000_000L,1000);
        for(BigInteger bad:List.of(ZERO,FuturesMath.PRICE_LIMIT)) {
            Report r=change(change(original,"priceE8",bad),"entryPriceE8",bad);
            assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.OPEN,r,Position.empty(),1000)));
        }
        for(BigInteger bad:List.of(ZERO,FuturesMath.AMOUNT_LIMIT)) {
            Report r=change(original,"collateralAfter",bad);
            assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.OPEN,r,Position.empty(),1000)));
        }
        Report leverage=change(original,"leverage",bi(3));
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.OPEN,leverage,Position.empty(),1000)));
    }
    @Test void nonePrestateMustActuallyBePristineDefaultStorage() {
        Report r=open(0,10,1000,10_000_000_000L,1000);
        for(String field:List.of("side","leverage","quantity","entryPriceE8","collateral","version","callEpoch","deadline","lastObservedAt")) {
            Position malformed=change(Position.empty(),field,ONE);
            assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.OPEN,r,malformed,1000)));
        }
        Position account=change(Position.empty(),"accountRef",ACCOUNT);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.OPEN,r,account,1000)));
    }
    @Test void activePointerAndTerminalPositionCannotBeSilentlySubstituted() {
        Position p=active();Report close=report(Action.CLOSE,p,10_000_000_000L,1000,1000);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(new Input(Action.CLOSE,close,TERMS,p,bi(1000),false,ZERO_HASH,keys(close))));
        Report open=open(0,10,1000,10_000_000_000L,1000);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(new Input(Action.OPEN,open,TERMS,Position.empty(),bi(1000),false,POS,keys(open))));
        Position terminal=run(Action.CLOSE,p,10_000_000_000L,1000,1000).after();
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.TOP_UP,report(Action.TOP_UP,terminal,10_000_000_000L,1100,1000),terminal,1000)));
    }
    @Test void reportIdentityActionAndSideMutationsAreRejected() {
        Report original=open(0,10,1000,10_000_000_000L,1000);
        for(String field:List.of("operationId","positionId","accountRef")) {
            Report r=change(original,field,ZERO_HASH);
            assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.OPEN,r,Position.empty(),1000)));
        }
        for(String field:List.of("symbolHash","policyHash")) {
            Report r=change(original,field,hash(999));
            assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.OPEN,r,Position.empty(),1000)));
        }
        for(String field:List.of("side","action")) {
            Report r=change(original,field,bi(255));
            assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.OPEN,r,Position.empty(),1000)));
        }
        Report executor=change(original,"executor","0x0000000000000000000000000000000000000999");
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.OPEN,executor,Position.empty(),1000)));
    }
    @Test void everyExistingPositionBoundFieldMustMatchReport() {
        Position p=active();Report close=report(Action.CLOSE,p,10_000_000_000L,1000,1000);
        for(String field:List.of("expectedVersion","expectedCallEpoch","quantity","entryPriceE8","collateralBefore","collateralAfter","leverage","side")) {
            BigInteger value=(BigInteger)component(close,field);Report changed=change(close,field,value.add(ONE));
            assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.CLOSE,changed,p,1000)),field);
        }
        Report account=change(close,"accountRef",hash(999));
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.CLOSE,account,p,1000)));
    }
    @Test void openRequiresZeroBeforeVersionEpochExactEntryAndCalculatedQuantity() {
        Report open=open(0,10,1000,10_000_000_000L,1000);
        for(String field:List.of("expectedVersion","expectedCallEpoch","collateralBefore","entryPriceE8","quantity")) {
            Report bad=change(open,field,((BigInteger)component(open,field)).add(ONE));
            assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.OPEN,bad,Position.empty(),1000)),field);
        }
        // Rounding makes E=MM=1 for this one-wei opening, so OPEN must reject risk.
        Report tiny=open(0,2,1,100_000_000,1000);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.OPEN,tiny,Position.empty(),1000)));
    }
    @Test void operationConsumedFlagRejectsWithoutAlteringOriginalInput() {
        Report r=open(0,10,1000,10_000_000_000L,1000);Position before=Position.empty();
        Input input=new Input(Action.OPEN,r,TERMS,before,bi(1000),true,ZERO_HASH,keys(r));
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input));assertEquals(Position.empty(),before);
        assertEquals(r,input.report());assertTrue(input.operationUsed());
    }
    @Test void versionIncrementOverflowIsRejectedInsteadOfWrapping() {
        Position p=change(active(),"version",MAX);Report close=report(Action.CLOSE,p,10_000_000_000L,1000,1000);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.CLOSE,close,p,1000)));
    }
    @Test void epochAndGraceDeadlineIncrementsCannotOverflow() {
        Position p=change(change(active(),"version",MAX),"callEpoch",MAX);
        assertThrows(IllegalArgumentException.class,()->run(Action.START_CALL,p,8_000_000_000L,1000,1000));
        BigInteger now=MAX.subtract(bi(40));Report base=report(Action.START_CALL,active(),8_000_000_000L,1000,1000);
        Report r=change(change(base,"observedAt",now),"validUntil",now.add(bi(30)));
        assertThrows(IllegalArgumentException.class,()->verifier.verify(new Input(Action.START_CALL,r,TERMS,active(),now,false,POS,keys(r))));
    }
    @Test void allElevenOperationAppliedFieldsAreComparedNotJustReducedAccountingSubset() {
        Position p=active();Input input=input(Action.CLOSE,report(Action.CLOSE,p,8_000_000_000L,1000,1000),p,1000);
        Result expected=verifier.verify(input);OperationApplied event=expected.operationApplied();
        assertEquals(expected,verifier.verifyEvents(input,event,expected.reportConsumed()));
        for(String field:List.of("operationId","positionId","action","version","priceE8","collateral","unrealized","realized","badDebt","callEpoch","deadline")) {
            Object old=component(event,field);Object changed=old instanceof BigInteger n?n.add(ONE):hash(999);
            OperationApplied wrong=change(event,field,changed);
            assertThrows(IllegalArgumentException.class,()->verifier.verifyEvents(input,wrong,expected.reportConsumed()),field);
        }
    }
    @Test void allReportConsumedFieldsAreComparedWithCalldata() {
        Position p=active();Input input=input(Action.CLOSE,report(Action.CLOSE,p,10_000_000_000L,1000,1000),p,1000);
        Result expected=verifier.verify(input);
        for(String field:List.of("operationId","observedAt","validUntil")) {
            Object old=component(expected.reportConsumed(),field);Object changed=old instanceof BigInteger n?n.add(ONE):hash(999);
            FuturesReportConsumed wrong=change(expected.reportConsumed(),field,changed);
            assertThrows(IllegalArgumentException.class,()->verifier.verifyEvents(input,expected.operationApplied(),wrong),field);
        }
    }
    @Test void shortRiskAndExactEquityMaintenanceBoundaryUseSameRules() {
        Position shortP=verifier.verify(input(Action.OPEN,open(1,10,1000,10_000_000_000L,1000),Position.empty(),1000)).after();
        Result shortCall=run(Action.START_CALL,shortP,12_000_000_000L,1000,1000);
        assertEquals(bi(-2000),shortCall.mark().unrealized());assertEquals(bi(600),shortCall.mark().maintenance());
        Result shortLiq=run(Action.LIQUIDATE,shortCall.after(),12_000_000_000L,1000,1060);
        assertEquals(bi(-1000),shortLiq.operationApplied().realized());assertEquals(bi(1000),shortLiq.operationApplied().badDebt());
        Position tiny=verifier.verify(input(Action.OPEN,open(0,2,2,100_000_000,1000),Position.empty(),1000)).after();
        Result equality=run(Action.START_CALL,tiny,75_000_000,2,1000);
        assertEquals(ONE,equality.mark().equity());assertEquals(ONE,equality.mark().maintenance());assertTrue(equality.mark().atRisk());
    }
    @Test void failedFinancialTransitionLeavesInputRecordsUnchangedAndScopeDoesNotOverclaim() {
        Position p=active();Report r=report(Action.TOP_UP,p,10_000_000_000L,1000,1000);Input input=input(Action.TOP_UP,r,p,1000);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input));assertEquals(active(),p);assertEquals(r,input.report());
        Result result=run(Action.CLOSE,p,10_000_000_000L,1000,1000);
        assertFalse(result.completeCoverage());assertTrue(result.verificationScope().contains("PREVALIDATED_CONTEXT_ONLY"));
        assertTrue(result.verificationScope().contains("no Oracle/user signature"));
    }
    @Test void invalidPrestateNumericStatusAndTermsDoNotBecomeDefaultValues() {
        Position p=active();Report r=report(Action.CLOSE,p,10_000_000_000L,1000,1000);
        for(String field:List.of("quantity","collateral","entryPriceE8","version")) {
            Position bad=change(p,field,ZERO);assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.CLOSE,r,bad,1000)));
        }
        Position nullStatus=change(p,"status",null);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(input(Action.CLOSE,r,nullStatus,1000)));
        Terms zero=new Terms("0x"+"0".repeat(40),SYMBOL,POLICY);
        assertThrows(IllegalArgumentException.class,()->verifier.verify(new Input(Action.CLOSE,r,zero,p,bi(1000),false,POS,keys(r))));
        for(String field:List.of("positionId","accountRef","operationId")) {
            PrestateKeys wrong=change(keys(r),field,hash(999));
            assertThrows(IllegalArgumentException.class,()->verifier.verify(new Input(Action.CLOSE,r,TERMS,p,bi(1000),false,POS,wrong)),field);
        }
    }

    private Position active() {return verifier.verify(input(Action.OPEN,open(0,10,1000,10_000_000_000L,1000),Position.empty(),1000)).after();}
    private Result run(Action action,Position p,long price,long after,long now) {return verifier.verify(input(action,report(action,p,price,after,now),p,now));}
    private static Input input(Action action,Report report,Position p,long now) {
        return input(action,report,p,bi(now));
    }
    private static Input input(Action action,Report report,Position p,BigInteger now) {
        return new Input(action,report,TERMS,p,now,false,p.status()==Status.ACTIVE||p.status()==Status.MARGIN_CALL?POS:ZERO_HASH,keys(report));
    }
    private static Report open(int side,int leverage,long collateral,long price,long observed) {
        Report base=report(Action.OPEN,Position.empty(),price,collateral,observed);
        return change(change(change(base,"side",bi(side)),"leverage",bi(leverage)),"quantity",FuturesMath.quantity(bi(collateral),leverage,bi(price)));
    }
    private static Report report(Action action,Position p,long price,long after,long observed) {
        boolean open=action==Action.OPEN;
        return new Report(hash(100+p.version().intValue()+action.ordinal()),POS,ACCOUNT,SYMBOL,action.code(),p.version(),p.callEpoch(),
            open?ZERO:p.side(),open?bi(10):p.leverage(),open?FuturesMath.quantity(bi(after),10,bi(price)):p.quantity(),
            open?bi(price):p.entryPriceE8(),p.collateral(),bi(after),bi(price),bi(observed),bi(observed+30),POLICY,EXEC);
    }
    private static BigInteger bi(long value) {return BigInteger.valueOf(value);}
    private static PrestateKeys keys(Report r) {return new PrestateKeys(r.positionId(),r.accountRef(),r.operationId());}
    private static String hash(int value) {return "0x"+String.format("%064x",value);}
    private static Object component(Record record,String name) {
        try {return record.getClass().getMethod(name).invoke(record);}catch(Exception e){throw new AssertionError(e);}
    }
    @SuppressWarnings("unchecked") private static <T extends Record>T change(T record,String name,Object value) {
        try {
            RecordComponent[] fields=record.getClass().getRecordComponents();Class<?>[] types=new Class<?>[fields.length];Object[] values=new Object[fields.length];boolean found=false;
            for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();values[i]=fields[i].getName().equals(name)?value:fields[i].getAccessor().invoke(record);found|=fields[i].getName().equals(name);}
            if(!found)throw new AssertionError("Unknown test field");return (T)record.getClass().getConstructor(types).newInstance(values);
        }catch(Exception e){throw new AssertionError("Test fixture construction",e);}
    }
}
