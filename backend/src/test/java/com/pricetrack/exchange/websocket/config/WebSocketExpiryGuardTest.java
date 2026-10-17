package com.pricetrack.exchange.websocket.config;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

class WebSocketExpiryGuardTest {
    @Test void dropsExpiredFramesAtEnqueueAndAfterExecutorQueueWithoutWaitingForTransportClose() {
        var guard=new WebSocketExpiryGuard();
        guard.authenticate("fixture-session",Instant.now().plusMillis(150));
        var headers=SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);headers.setSessionId("fixture-session");
        var frame=MessageBuilder.createMessage("private".getBytes(),headers.getMessageHeaders());
        assertThat(guard.preSend(frame,null)).isSameAs(frame);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2)).until(()->guard.expired("fixture-session"));
        assertThat(guard.preSend(frame,null)).isNull();
        assertThat(guard.beforeHandle(frame,null,null)).isNull();
    }
    @Test void keepsAnonymousPublicSessionsAndRejectsInvalidExpiry() {
        var guard=new WebSocketExpiryGuard();var headers=SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);headers.setSessionId("anonymous");
        var frame=MessageBuilder.createMessage(new byte[0],headers.getMessageHeaders());
        assertThat(guard.preSend(frame,null)).isSameAs(frame);
        assertThatThrownBy(()->guard.authenticate("bad",Instant.now().minusSeconds(1)))
                .isInstanceOf(org.springframework.messaging.MessageDeliveryException.class);
    }
}
