package com.pricetrack.exchange.futures.runtime;

import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.util.Optional;

/** Pure decision only. The server worker submits a transaction; Solidity independently enforces every transition. */
public final class FuturesRiskEvaluator {
    public Optional<Action> evaluate(Position p,FuturesMarketGate.Mark mark,long serverNow,long chainNow) {
        if(!p.status().equals("OPEN") || p.pendingOperation()!=null || mark.observedAt().getEpochSecond()<p.lastObservedAt()) return Optional.empty();
        boolean risky=FuturesMath.mark(p.side(),p.quantity(),p.entryPrice(),p.collateral(),mark.price()).atRisk();
        if(p.riskStatus().equals("HEALTHY")) return risky?Optional.of(Action.START_CALL):Optional.empty();
        if(!p.riskStatus().equals("GRACE")) return Optional.empty();
        if(!risky) return Optional.of(Action.CLEAR_CALL);
        long observed=mark.observedAt().getEpochSecond();
        return chainNow>=p.deadline() && observed>=p.deadline() && observed<=serverNow && observed<=chainNow
            ?Optional.of(Action.LIQUIDATE):Optional.empty();
    }
}
