package com.pricetrack.exchange.futures.runtime;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath.Side;
import java.math.BigInteger;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FuturesRiskEvaluatorTest {
    final FuturesRiskEvaluator evaluator=new FuturesRiskEvaluator();
    final BigInteger c=new BigInteger("100000000000000000000000"),q=new BigInteger("13333333333333333333");
    Position p(Side side,String risk,long deadline,String pending,String status) {return new Position("p",1,"a",side,10,q,new BigInteger("7500000000000"),c,status,risk,2,1,deadline,1000,pending,null,null,null,Instant.EPOCH);}
    FuturesMarketGate.Mark mark(int price,long time) {return new FuturesMarketGate.Mark(BigInteger.valueOf(price).multiply(BigInteger.TEN.pow(8)),Instant.ofEpochSecond(time),"SIMULATED",null);}
    @Test void longAndShortAutomaticallyStartCallOnOppositeRiskDirections() {
        assertThat(evaluator.evaluate(p(Side.LONG,"HEALTHY",0,null,"OPEN"),mark(70000,1001),1001,1001)).contains(Action.START_CALL);
        assertThat(evaluator.evaluate(p(Side.SHORT,"HEALTHY",0,null,"OPEN"),mark(80000,1001),1001,1001)).contains(Action.START_CALL);
    }
    @Test void healthyPositionsHaveNoAction() {for(var side:Side.values())assertThat(evaluator.evaluate(p(side,"HEALTHY",0,null,"OPEN"),mark(75000,1001),1001,1001)).isEmpty();}
    @Test void recoveryClearsEvenAfterDeadlineInsteadOfLiquidating() {assertThat(evaluator.evaluate(p(Side.LONG,"GRACE",1060,null,"OPEN"),mark(74000,1061),1061,1061)).contains(Action.CLEAR_CALL);}
    @Test void requiresFreshPostDeadlineObservationAndActualChainTime() {
        var p=p(Side.LONG,"GRACE",1060,null,"OPEN");
        assertThat(evaluator.evaluate(p,mark(60000,1059),1061,1061)).isEmpty();
        assertThat(evaluator.evaluate(p,mark(60000,1060),1060,1059)).isEmpty();
        assertThat(evaluator.evaluate(p,mark(60000,1061),1060,1061)).isEmpty();
        assertThat(evaluator.evaluate(p,mark(60000,1060),1060,1060)).contains(Action.LIQUIDATE);
    }
    @Test void pendingReviewTerminalAndOldSamplesNeverProduceAnotherAction() {
        assertThat(evaluator.evaluate(p(Side.LONG,"GRACE",1060,"op","OPEN"),mark(60000,1061),1061,1061)).isEmpty();
        for(String s:new String[]{"LIQUIDATED","CLOSED","REVIEW_REQUIRED","OPEN_PENDING"})assertThat(evaluator.evaluate(p(Side.LONG,"GRACE",1060,null,s),mark(60000,1061),1061,1061)).isEmpty();
        assertThat(evaluator.evaluate(p(Side.LONG,"HEALTHY",0,null,"OPEN"),mark(60000,999),1000,1000)).isEmpty();
    }
}
