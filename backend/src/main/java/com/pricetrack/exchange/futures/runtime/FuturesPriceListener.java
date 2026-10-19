package com.pricetrack.exchange.futures.runtime;

import com.pricetrack.exchange.websocket.event.*;
import org.springframework.transaction.event.*;

/** Synthetic publisher runs only after price persistence COMMIT. Never runs RPC in the price event thread. */
public final class FuturesPriceListener {
    private final FuturesWorker worker;
    public FuturesPriceListener(FuturesWorker worker) { this.worker=worker; }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
    public void committedPrice(WebSocketDelivery delivery) {
        if(!delivery.privateDelivery() && delivery.destination().equals(WebSocketDestinations.PRICE_TOPIC)
            && delivery.event().type()==WebSocketEventType.PRICE_UPDATED)worker.signal();
    }
}
