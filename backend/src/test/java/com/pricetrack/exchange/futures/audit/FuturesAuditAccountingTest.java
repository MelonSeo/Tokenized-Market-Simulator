package com.pricetrack.exchange.futures.audit;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.ledger.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import com.pricetrack.exchange.reserve.*;
import com.pricetrack.exchange.reserve.ReserveModels.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class FuturesAuditAccountingTest {
    static final BigInteger Z=BigInteger.ZERO,C=BigInteger.TEN.pow(23),P=BigInteger.valueOf(75000).multiply(BigInteger.TEN.pow(8));
    static final String REF="0x"+"1".repeat(64);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    static FuturesAuditSnapshot empty(){return new FuturesAuditSnapshot(FuturesLedger.POLICY_HASH,FuturesLedger.INITIAL_CAPITAL,FuturesLedger.INITIAL_CAPITAL,Z,List.of(),List.of(),List.of(),List.of(),List.of());}
    static Position position(String status,long version,String pending){return new Position("p",1,REF,Side.LONG,10,FuturesMath.quantity(C,10,P),P,C,status,"HEALTHY",version,0,0,100,pending,null,null,null,Instant.EPOCH);}
    static Operation open(String state){return new Operation("o","p",1,Action.OPEN,0,0,P,100,Z,C,C,Z,Z,Z,state);}
    static FuturesAuditSnapshot.Journal journal(String type,BigInteger wallet,BigInteger lock,BigInteger house){return new FuturesAuditSnapshot.Journal(type,"o","p",1,type,wallet,lock,house,Z);}
    FuturesImpact impact(FuturesAuditSnapshot s){return FuturesAuditAccounting.impact(s,json);}
    @Test void freshPolicyIsConservedAndNoCollateralEscrowIsInvented(){var i=impact(empty());assertThat(i.checks()).allMatch(Check::matches);assertThat(i.locks()).isEmpty();assertThat(i.walletDeltas()).isEmpty();}
    @Test void pendingOpenLocksMarginButDoesNotDebitAmount(){
        var s=new FuturesAuditSnapshot(empty().policy(),empty().initial(),empty().house(),Z,List.of(position("OPEN_PENDING",0,"o")),List.of(open("PREPARED")),List.of(),List.of(journal("RESERVE",Z,C,Z)),List.of(new FuturesAuditSnapshot.Guard(1,REF,"p")));
        var i=impact(s);assertThat(i.checks()).allMatch(Check::matches);assertThat(i.unsettled()).isTrue();assertThat(i.locks()).containsEntry(1L,C);assertThat(i.walletDeltas()).isEmpty();
        var cut=new DbCut(Instant.EPOCH,List.of(new Balance(1,"mKRW",C.multiply(BigInteger.TEN),C)),List.of(),List.of(),List.of(),List.of(new Grant("g",1,C.multiply(BigInteger.TEN),Instant.EPOCH)),i);
        assertThat(ReserveCalculator.walletChecks(cut)).allMatch(Check::matches);
    }
    @Test void rejectedOpenReleasesOnlyReservedMargin(){
        var s=new FuturesAuditSnapshot(empty().policy(),empty().initial(),empty().house(),Z,List.of(position("OPEN_FAILED",0,null)),List.of(open("REJECTED")),List.of(),List.of(journal("RESERVE",Z,C,Z),journal("RELEASE",Z,C.negate(),Z)),List.of(new FuturesAuditSnapshot.Guard(1,REF,null)));
        assertThat(impact(s).checks()).allMatch(Check::matches);assertThat(impact(s).locks()).containsEntry(1L,Z);assertThat(s.unsettled()).isFalse();
    }
    @Test void duplicateJournalAndHouseTamperAreMismatches(){
        var s=new FuturesAuditSnapshot(empty().policy(),empty().initial(),empty().house().subtract(C),Z,List.of(position("OPEN_PENDING",0,"o")),List.of(open("PREPARED")),List.of(),List.of(journal("RESERVE",Z,C,Z),journal("RESERVE",Z,C,Z)),List.of(new FuturesAuditSnapshot.Guard(1,REF,"p")));
        assertThat(impact(s).checks()).anyMatch(c->c.name().equals("FUTURES_JOURNAL_COUNT_o:RESERVE")&&!c.matches()).anyMatch(c->c.name().equals("FUTURES_HOUSE")&&!c.matches());
    }
    @Test void futuresPnlChangesDbOnlyNotOperatorOrVault(){
        var i=new FuturesImpact(Map.of(1L,C),Map.of(),List.of(),"hash",false,false);
        var cut=new DbCut(Instant.EPOCH,List.of(new Balance(1,"mKRW",C.multiply(BigInteger.TWO),Z)),List.of(),List.of(),List.of(),List.of(new Grant("g",1,C,Instant.EPOCH)),i);
        assertThat(ReserveCalculator.walletChecks(cut)).allMatch(Check::matches);
        var insufficient=new DbCut(Instant.EPOCH,List.of(new Balance(1,"mKRW",C,Z)),List.of(),List.of(),List.of(),cut.grants(),i);
        assertThat(ReserveCalculator.walletChecks(insufficient)).anyMatch(c->!c.matches());
    }
    @Test void missingGuardOrIncorrectPolicyIsNotMatch(){
        var s=new FuturesAuditSnapshot("wrong",empty().initial(),empty().house(),Z,List.of(position("OPEN_PENDING",0,"o")),List.of(open("PREPARED")),List.of(),List.of(journal("RESERVE",Z,C,Z)),List.of());
        assertThat(impact(s).checks()).anyMatch(c->c.name().equals("FUTURES_POLICY")&&!c.matches()).anyMatch(c->c.name().equals("FUTURES_GUARD_p")&&!c.matches());
    }
    Position settlement(String status,BigInteger realized,BigInteger debt){var p=position(status,2,null);return new Position(p.id(),p.userId(),p.accountRef(),p.side(),p.leverage(),p.quantity(),p.entryPrice(),p.collateral(),p.status(),p.riskStatus(),p.version(),p.callEpoch(),p.deadline(),p.lastObservedAt(),null,null,realized,debt,p.createdAt());}
    @Test void terminalPnlDebtAndActionCannotBeTamperedIndependently(){
        var close=new Operation("end","p",1,Action.CLOSE,1,0,P,110,C,C,Z,C,C,Z,"CONFIRMED");
        assertThat(FuturesAuditAccounting.settlementMatches(settlement("CLOSED",C,Z),List.of(close))).isTrue();
        assertThat(FuturesAuditAccounting.settlementMatches(settlement("CLOSED",Z,Z),List.of(close))).isFalse();
        assertThat(FuturesAuditAccounting.settlementMatches(settlement("CLOSED",C,BigInteger.ONE),List.of(close))).isFalse();
        assertThat(FuturesAuditAccounting.settlementMatches(settlement("LIQUIDATED",C,Z),List.of(close))).isFalse();
        assertThat(FuturesAuditAccounting.settlementMatches(settlement("CLOSED",C,Z),List.of())).isFalse();
    }
    @Test void nonterminalAndFailedOpenMustNotInventRealizedPnl(){for(String state:List.of("OPEN","OPEN_PENDING","OPEN_FAILED","CLOSING","REVIEW_REQUIRED")){assertThat(FuturesAuditAccounting.settlementMatches(settlement(state,null,null),List.of())).isTrue();assertThat(FuturesAuditAccounting.settlementMatches(settlement(state,Z,Z),List.of())).isFalse();}}
}
