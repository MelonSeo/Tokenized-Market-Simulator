package com.pricetrack.exchange.futures.runtime;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.futures.ledger.FuturesLedger;
import com.pricetrack.exchange.websocket.publisher.WebSocketEventPublisher;
import com.pricetrack.exchange.websocket.event.*;
import org.junit.jupiter.api.Test;

class FuturesPersonalEventsTest {
    @Test void sendsOnlyTheOwnerAndNoFinancialOrRawDetails() {
        var p=mock(WebSocketEventPublisher.class);new FuturesPersonalEvents(p).committed(new FuturesLedger.Changed(7,"p","o"));
        verify(p).publishToUser(eq(7L),eq(WebSocketDestinations.FUTURES_QUEUE),eq(WebSocketEventType.FUTURES_UPDATED),eq(java.util.Map.of("positionId","p","operationId","o")));
    }
    @Test void notificationFailureCannotReplaceACommittedSettlement() {
        var p=mock(WebSocketEventPublisher.class);doThrow(new IllegalStateException("fixture")).when(p).publishToUser(anyLong(),anyString(),any(),any());
        var events=new FuturesPersonalEvents(p);events.committed(new FuturesLedger.Changed(7,"p","o"));assertThat(events.failureCount()).isEqualTo(1);
    }
}
