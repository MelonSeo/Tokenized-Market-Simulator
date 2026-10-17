package com.pricetrack.exchange.websocket.config;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/** Expiry is checked on outbound delivery as well as inbound commands; transport closure is cleanup. */
@Component
public class WebSocketExpiryGuard implements org.springframework.messaging.support.ExecutorChannelInterceptor {
    private final ConcurrentHashMap<String, Instant> expirations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebSocketSession> transports = new ConcurrentHashMap<>();
    public void authenticate(String sessionId, Instant expiration) {
        if (sessionId == null || expiration == null || !expiration.isAfter(Instant.now()))
            throw new MessageDeliveryException("WebSocket authentication expired");
        expirations.put(sessionId, expiration);
    }
    public boolean expired(String sessionId) {
        Instant expiry = sessionId == null ? null : expirations.get(sessionId);
        return expiry != null && !expiry.isAfter(Instant.now());
    }
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        return expired(SimpMessageHeaderAccessor.getSessionId(message.getHeaders())) ? null : message;
    }
    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        // clientOutboundChannel uses an executor: recheck after queueing, immediately before delivery.
        return preSend(message, channel);
    }
    public WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                transports.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }
            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                try { super.afterConnectionClosed(session, status); }
                finally { transports.remove(session.getId()); expirations.remove(session.getId()); }
            }
        };
    }
    @Scheduled(fixedDelay = 1000)
    public void closeExpiredTransports() {
        transports.forEach((id, session) -> {
            if (expired(id)) {
                try { session.close(CloseStatus.POLICY_VIOLATION); }
                catch (java.io.IOException ignored) { /* Outbound guard still blocks delivery. */ }
            }
        });
    }
}
