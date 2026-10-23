package com.pricetrack.exchange.futures.audit;

import static org.assertj.core.api.Assertions.*;
import static com.pricetrack.exchange.futures.audit.FuturesAuditNonceFold.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class FuturesAuditNonceFoldTest {
    private static final BigInteger MAX=BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    private static String address(int n){return "0x"+String.format("%040x",n);}
    private static String hash(int n){return "0x"+String.format("%064x",n);}
    private static Group group(){return new Group(BigInteger.valueOf(31337),address(1),address(2));}
    private static Window window(Group g,long from,long to,BigInteger start,BigInteger end){return new Window(g,from,to,start,end);}
    private static Window window(long start,long end){return window(group(),10,20,BigInteger.valueOf(start),BigInteger.valueOf(end));}
    private static Transition action(Group g,long block,long tx,long log,int txHash,Kind kind,Outcome outcome,BigInteger nonce){
        return new Transition(g,block,tx,outcome==Outcome.SUCCESS?log:null,hash((int)block),hash(txHash),kind,outcome,nonce,true);
    }
    private static Transition intent(long block,long tx,long nonce){return action(group(),block,tx,tx*3,(int)(block*100+tx),Kind.USER_INTENT,Outcome.SUCCESS,BigInteger.valueOf(nonce));}
    private static Transition cancel(long block,long tx,long nonce){return action(group(),block,tx,tx*3+1,(int)(block*100+tx),Kind.CANCEL,Outcome.SUCCESS,BigInteger.valueOf(nonce));}
    private static Result fold(Window w,Transition... t){return FuturesAuditNonceFold.fold(List.of(w),Arrays.asList(t),true);}
    private static void unknown(Result result,Reason reason){assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);assertThat(result.reason()).isEqualTo(reason);assertThat(result.nonceCoverageComplete()).isFalse();assertThat(result.groups()).isEmpty();}
    private static void mismatch(Result result,Reason reason){assertThat(result.verdict()).isEqualTo(Verdict.MISMATCH);assertThat(result.nonceCoverageComplete()).isTrue();assertThat(result.groups().getFirst().reason()).isEqualTo(reason);}

    @Test void foldsIntentThenCancellationFromZero(){
        var result=fold(window(0,2),intent(11,0,0),cancel(12,0,1));
        assertThat(result.verdict()).isEqualTo(Verdict.MATCH);assertThat(result.groups().getFirst().calculatedNonce()).isEqualTo(BigInteger.TWO);
        assertThat(result.groups().getFirst().successfulIntents()).isEqualTo(1);assertThat(result.groups().getFirst().successfulCancellations()).isEqualTo(1);
    }
    @Test void nonzeroBaselineAndReverseInputAreSortedByCanonicalCoordinates(){
        var result=fold(window(7,10),intent(12,1,9),cancel(11,1,8),intent(11,0,7));
        assertThat(result.verdict()).isEqualTo(Verdict.MATCH);assertThat(result.groups().getFirst().foldedTransitions()).isEqualTo(3);
    }
    @Test void cancellationThenIntentAndMultipleTransactionsInOneBlockAreValid(){assertThat(fold(window(0,2),intent(11,1,1),cancel(11,0,0)).verdict()).isEqualTo(Verdict.MATCH);}
    @Test void blockGlobalLogIndexCannotBeClaimedByDifferentTransactions(){
        var second=action(group(),11,1,0,1101,Kind.USER_INTENT,Outcome.SUCCESS,BigInteger.ONE);
        unknown(fold(window(0,2),intent(11,0,0),second),Reason.CANONICAL_ORDER_CONFLICT);
    }
    @Test void blockGlobalLogIndexMustIncreaseWithTransactionIndex(){
        var first=action(group(),11,0,5,1100,Kind.USER_INTENT,Outcome.SUCCESS,BigInteger.ZERO);
        var second=action(group(),11,1,4,1101,Kind.CANCEL,Outcome.SUCCESS,BigInteger.ONE);
        unknown(fold(window(0,2),first,second),Reason.CANONICAL_ORDER_CONFLICT);
    }
    @Test void multipleLogicalEventsInSameTransactionThenNextBlockUseCanonicalLogOrder(){
        var first=action(group(),11,0,2,1100,Kind.USER_INTENT,Outcome.SUCCESS,BigInteger.ZERO);
        var second=action(group(),11,0,3,1100,Kind.CANCEL,Outcome.SUCCESS,BigInteger.ONE);
        var third=action(group(),11,1,7,1101,Kind.AUTOMATIC,Outcome.SUCCESS,null);
        assertThat(fold(window(0,3),cancel(12,0,2),third,second,first).verdict()).isEqualTo(Verdict.MATCH);
    }
    @Test void automaticEventAlsoParticipatesInBlockGlobalLogUniqueness(){
        var auto=action(group(),11,1,0,1101,Kind.AUTOMATIC,Outcome.SUCCESS,null);
        unknown(fold(window(0,1),intent(11,0,0),auto),Reason.CANONICAL_ORDER_CONFLICT);
    }
    @Test void successAutomaticAndFailedUserDoNotConsumeUserNonce(){
        var auto=action(group(),12,0,0,1200,Kind.AUTOMATIC,Outcome.SUCCESS,null);
        var failed=action(group(),13,0,0,1300,Kind.USER_INTENT,Outcome.FAILED,null);
        var failedCancel=action(group(),14,0,0,1400,Kind.CANCEL,Outcome.FAILED,null);
        var result=fold(window(3,4),intent(11,0,3),auto,failed,failedCancel);
        assertThat(result.verdict()).isEqualTo(Verdict.MATCH);assertThat(result.groups().getFirst().nonAdvancing()).isEqualTo(3);
    }
    @Test void emptyCoveredWindowNeedsNoEventsWhenHistoricalNoncesAgree(){assertThat(fold(window(group(),10,10,BigInteger.valueOf(8),BigInteger.valueOf(8))).verdict()).isEqualTo(Verdict.MATCH);}
    @Test void emptyTargetUniverseIsNotProof(){unknown(FuturesAuditNonceFold.fold(List.of(),List.of(),true),Reason.INPUT_REQUIRED);}
    @Test void nonceGapIsMismatchOnlyWithExplicitCompleteCoverage(){mismatch(fold(window(0,2),intent(11,0,1)),Reason.NONCE_GAP);}
    @Test void repeatedConsumedNonceIsMismatch(){mismatch(fold(window(0,2),intent(11,0,0),cancel(12,0,0)),Reason.NONCE_REUSE);}
    @Test void finalHistoricalNonceConflictIsMismatch(){mismatch(fold(window(0,2),intent(11,0,0)),Reason.FINAL_NONCE_CONFLICT);}
    @Test void missingCoverageNeverReturnsFinancialOrNonceMatch(){unknown(FuturesAuditNonceFold.fold(List.of(window(0,0)),List.of(),false),Reason.COVERAGE_REQUIRED);}
    @Test void duplicateEvidenceIsNotDeduplicatedIntoMatch(){var t=intent(11,0,0);unknown(fold(window(0,1),t,t),Reason.DUPLICATE_EVIDENCE);}
    @Test void conflictingTransactionHashesAtSameGroupCoordinatesAreInconclusive(){
        var other=action(group(),11,0,0,9999,Kind.CANCEL,Outcome.SUCCESS,BigInteger.ONE);
        unknown(fold(window(0,2),intent(11,0,0),other),Reason.CANONICAL_ORDER_CONFLICT);
    }
    @Test void sameTransactionHashCannotOccupyTwoCanonicalBlocks(){
        var t=intent(11,0,0);var moved=new Transition(group(),12,0,0L,hash(12),t.txHash(),Kind.CANCEL,Outcome.SUCCESS,BigInteger.ONE,true);
        unknown(fold(window(0,2),t,moved),Reason.CANONICAL_ORDER_CONFLICT);
    }
    @Test void conflictingBlockHashesAtSameHeightAreInconclusive(){
        var t=new Transition(group(),11,1,0L,hash(999),hash(1101),Kind.CANCEL,Outcome.SUCCESS,BigInteger.ONE,true);
        unknown(fold(window(0,2),intent(11,0,0),t),Reason.CANONICAL_ORDER_CONFLICT);
    }
    @Test void contradictoryOutcomesInSameTransactionAreInconclusive(){
        var t=intent(11,0,0);var failed=new Transition(group(),11,0,null,t.blockHash(),t.txHash(),Kind.CANCEL,Outcome.FAILED,null,true);
        unknown(fold(window(0,1),t,failed),Reason.CANONICAL_ORDER_CONFLICT);
    }
    @Test void undeclaredOwnerIsNotSilentlyIgnored(){var g=new Group(BigInteger.valueOf(31337),address(1),address(3));unknown(fold(window(0,0),action(g,11,0,0,1100,Kind.CANCEL,Outcome.SUCCESS,BigInteger.ZERO)),Reason.UNDECLARED_GROUP);}
    @Test void baselineExclusiveAndCutInclusiveAreEnforced(){unknown(fold(window(0,1),intent(10,0,0)),Reason.OUTSIDE_WINDOW);unknown(fold(window(0,1),intent(21,0,0)),Reason.OUTSIDE_WINDOW);assertThat(fold(window(0,1),intent(20,0,0)).verdict()).isEqualTo(Verdict.MATCH);}
    @Test void ownersControllersAndChainsUseIndependentNonceWindows(){
        var groups=List.of(group(),new Group(BigInteger.valueOf(31337),address(1),address(3)),new Group(BigInteger.valueOf(31337),address(4),address(2)),new Group(BigInteger.valueOf(31338),address(1),address(2)));
        var windows=new ArrayList<Window>();var actions=new ArrayList<Transition>();
        for(int i=0;i<groups.size();i++){windows.add(window(groups.get(i),10,20,BigInteger.valueOf(i),BigInteger.valueOf(i+1)));actions.add(action(groups.get(i),11,i,i*3,1100+i,Kind.USER_INTENT,Outcome.SUCCESS,BigInteger.valueOf(i)));}
        var result=FuturesAuditNonceFold.fold(windows,actions,true);assertThat(result.verdict()).isEqualTo(Verdict.MATCH);assertThat(result.groups()).hasSize(4);
    }
    @Test void addressCaseIsNormalizedAndMalformedGroupsAreRejectedWithoutEchoingValues(){
        var upper=new Group(BigInteger.ONE,"0x"+"AB".repeat(20),"0x"+"CD".repeat(20));assertThat(upper.controller()).isEqualTo("0x"+"ab".repeat(20));assertThat(upper.owner()).isEqualTo("0x"+"cd".repeat(20));
        assertThatThrownBy(()->new Group(BigInteger.ZERO,address(1),address(2))).hasMessage("INVALID_NONCE_GROUP");
        assertThatThrownBy(()->new Group(BigInteger.ONE,"secret-malformed",address(2))).hasMessage("INVALID_NONCE_GROUP");
        assertThatThrownBy(()->new Group(BigInteger.ONE,address(0),address(2))).hasMessage("INVALID_NONCE_GROUP");
        assertThatThrownBy(()->new Group(BigInteger.ONE.shiftLeft(256),address(1),address(2))).hasMessage("INVALID_NONCE_GROUP");
    }
    @Test void uint256MaximumAnchorWithoutTransitionIsValidButAdvancingOverflows(){
        var w=window(group(),10,20,MAX,MAX);assertThat(fold(w).verdict()).isEqualTo(Verdict.MATCH);
        mismatch(fold(w,action(group(),11,0,0,1100,Kind.CANCEL,Outcome.SUCCESS,MAX)),Reason.NONCE_OVERFLOW);
    }
    @Test void negativeAndOversizedAnchorsAndEventNoncesFailClosed(){
        for(var invalid:List.of(BigInteger.valueOf(-1),MAX.add(BigInteger.ONE))){
            unknown(fold(window(group(),10,20,invalid,BigInteger.ZERO)),Reason.MALFORMED_WINDOW);
            unknown(fold(window(0,1),action(group(),11,0,0,1100,Kind.USER_INTENT,Outcome.SUCCESS,invalid)),Reason.MALFORMED_TRANSITION);
        }
    }
    @Test void fiftyTransitionsAreBoundedAndFiftyOneNeverPartiallyFold(){
        var actions=new ArrayList<Transition>();for(int i=0;i<50;i++)actions.add(intent(11,i,i));
        assertThat(FuturesAuditNonceFold.fold(List.of(window(0,50)),actions,true).verdict()).isEqualTo(Verdict.MATCH);
        actions.add(intent(11,50,50));unknown(FuturesAuditNonceFold.fold(List.of(window(0,51)),actions,true),Reason.BOUNDED_INPUT_LIMIT);
    }
    @Test void absentHistoricalAnchorAndDuplicateWindowAreInconclusive(){
        unknown(fold(window(group(),10,20,null,BigInteger.ZERO)),Reason.NONCE_ANCHOR_REQUIRED);
        unknown(FuturesAuditNonceFold.fold(List.of(window(0,0),window(0,0)),List.of(),true),Reason.DUPLICATE_WINDOW);
    }
    @Test void incompleteEvidenceAndUnsupportedKindNeverFold(){
        unknown(fold(window(0,1),new Transition(group(),11,0,0L,hash(11),hash(1100),Kind.CANCEL,Outcome.SUCCESS,BigInteger.ZERO,false)),Reason.TRANSITION_EVIDENCE_REQUIRED);
        unknown(fold(window(0,1),action(group(),11,0,0,1100,null,Outcome.SUCCESS,BigInteger.ZERO)),Reason.UNSUPPORTED_KIND);
    }
    @Test void malformedEventPresenceAndHashesAreInconclusive(){
        unknown(fold(window(0,1),new Transition(group(),11,0,null,hash(11),hash(1100),Kind.CANCEL,Outcome.SUCCESS,BigInteger.ZERO,true)),Reason.MALFORMED_TRANSITION);
        unknown(fold(window(0,0),new Transition(group(),11,0,0L,hash(11),hash(1100),Kind.CANCEL,Outcome.FAILED,null,true)),Reason.MALFORMED_TRANSITION);
        unknown(fold(window(0,0),action(group(),11,0,0,1100,Kind.AUTOMATIC,Outcome.SUCCESS,BigInteger.ZERO)),Reason.MALFORMED_TRANSITION);
        unknown(fold(window(0,1),new Transition(group(),11,0,0L,"secret-raw",hash(1100),Kind.CANCEL,Outcome.SUCCESS,BigInteger.ZERO,true)),Reason.MALFORMED_TRANSITION);
    }
    @Test void nullInputsAndInvalidWindowsAreInconclusive(){
        unknown(FuturesAuditNonceFold.fold(null,List.of(),true),Reason.INPUT_REQUIRED);
        unknown(fold(window(group(),20,10,BigInteger.ZERO,BigInteger.ZERO)),Reason.MALFORMED_WINDOW);
        unknown(FuturesAuditNonceFold.fold(List.of(window(0,0)),Arrays.asList((Transition)null),true),Reason.MALFORMED_TRANSITION);
    }
    @Test void resultIsImmutableNonceOnlySummaryNotRawProof(){
        var result=fold(window(0,1),intent(11,0,0));assertThatThrownBy(()->result.groups().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(Arrays.stream(GroupSummary.class.getRecordComponents()).map(c->c.getName())).doesNotContain("raw","signature","calldata","txHash");
        assertThat(result.toString()).doesNotContain(hash(1100));
    }
}
