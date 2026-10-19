package com.pricetrack.exchange.futures.audit;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class FuturesAuditVerifierTest {
    static String encoded(BigInteger... words){return "0x"+Arrays.stream(words).map(n->String.format("%064x",n)).reduce("",String::concat);}
    static String state(Position p,int status,long version,long deadline){return encoded(new BigInteger(p.accountRef().substring(2),16),BigInteger.valueOf(p.side().ordinal()),BigInteger.valueOf(p.leverage()),p.quantity(),p.entryPrice(),p.collateral(),BigInteger.valueOf(status),BigInteger.valueOf(version),BigInteger.ZERO,BigInteger.valueOf(deadline),BigInteger.valueOf(100));}
    @Test void openRequiresEmptyHistoricalState(){var p=FuturesAuditAccountingTest.position("OPEN",1,null);var o=FuturesAuditAccountingTest.open("CONFIRMED");assertThat(FuturesAuditVerifier.before(p,o,"0x"+"0".repeat(704)).version()).isZero();assertThatThrownBy(()->FuturesAuditVerifier.before(p,o,state(p,1,1,0))).isInstanceOf(Failure.class);}
    @Test void historicalRiskDoesNotUseTodaysPosition(){
        var p=FuturesAuditAccountingTest.position("CLOSED",3,null);var o=new Operation("c","p",1,Action.CLOSE,2,0,p.entryPrice(),110,p.collateral(),p.collateral(),BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,"CONFIRMED");
        var before=FuturesAuditVerifier.before(p,o,state(p,2,2,160));assertThat(before.riskStatus()).isEqualTo("GRACE");assertThat(before.deadline()).isEqualTo(160);assertThat(before.status()).isEqualTo("OPEN");
    }
    @Test void ambiguousSameBlockStorageCannotProveMismatch(){var p=FuturesAuditAccountingTest.position("CLOSED",3,null);var o=new Operation("c","p",1,Action.CLOSE,2,0,p.entryPrice(),110,p.collateral(),p.collateral(),BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,"CONFIRMED");assertThatThrownBy(()->FuturesAuditVerifier.before(p,o,state(p,1,1,0))).isInstanceOf(FuturesAuditVerifier.HistoricalCutUnavailable.class);}
    @Test void currentStateIncludesEveryFieldAndFailedOpenHasNoOnchainPosition(){var p=FuturesAuditAccountingTest.position("OPEN",1,null);assertThat(FuturesAuditVerifier.current(p,state(p,1,1,0))).isTrue();assertThat(FuturesAuditVerifier.current(p,state(p,1,2,0))).isFalse();assertThat(FuturesAuditVerifier.current(FuturesAuditAccountingTest.position("OPEN_FAILED",0,null),"0x"+"0".repeat(704))).isTrue();}
    @Test void malformedStorageCannotPass(){assertThatThrownBy(()->FuturesAuditVerifier.words("0x1234")).isInstanceOf(Failure.class);}
}
