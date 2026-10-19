package com.pricetrack.exchange.futures.runtime;

import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.websocket.publisher.WebSocketEventPublisher;
import com.pricetrack.exchange.websocket.event.*;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

/** Minimal invalidation, not an account dump. Clients re-read their own REST projection after COMMIT/reconnect. */
@Component @ConditionalOnProperty(name="app.futures.runtime-enabled",havingValue="true")
public class FuturesPersonalEvents {
    private final WebSocketEventPublisher events;
    private final java.util.concurrent.atomic.AtomicLong failures=new java.util.concurrent.atomic.AtomicLong();
    public FuturesPersonalEvents(WebSocketEventPublisher events) { this.events=events; }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void committed(FuturesLedger.Changed change) {
        try {events.publishToUser(change.userId(),WebSocketDestinations.FUTURES_QUEUE,WebSocketEventType.FUTURES_UPDATED,
            Map.of("positionId",change.positionId(),"operationId",change.operationId()));}
        catch(RuntimeException unavailable){failures.incrementAndGet();} // REST is authoritative; notification failure cannot undo COMMIT.
    }
    public long failureCount(){return failures.get();}
}
