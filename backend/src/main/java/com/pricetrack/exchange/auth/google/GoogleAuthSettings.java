package com.pricetrack.exchange.auth.google;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GoogleAuthSettings {
    private final boolean enabled;
    private final String clientId;
    private final Set<String> origins;
    public GoogleAuthSettings(@Value("${app.google.enabled:false}") boolean enabled,
            @Value("${app.google.client-id:}") String clientId,
            @Value("${app.google.allowed-origins:}") String origins) {
        this.enabled = enabled;
        this.clientId = clientId;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (enabled && (clientId.isBlank() || this.origins.isEmpty())) {
            throw new IllegalStateException("Google client ID and exact origins are required");
        }
        for (String origin : this.origins) {
            URI uri = URI.create(origin);
            boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
            if ((!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && local))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalStateException("Google origins must be exact HTTPS origins or localhost");
            }
        }
    }
    public boolean enabled() { return enabled; }
    public String clientId() { return clientId; }
    public boolean permits(String origin) { return origin != null && origins.contains(origin); }
    public void requireEnabled() {
        if (!enabled) throw new GoogleAuthFailure(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "GOOGLE_AUTH_DISABLED");
    }
}
