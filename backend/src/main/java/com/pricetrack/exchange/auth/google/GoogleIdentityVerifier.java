package com.pricetrack.exchange.auth.google;

import java.time.Instant;
import java.util.Set;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Google keys are fetched lazily from a fixed trusted endpoint, never token jku/iss. */
@Component
public class GoogleIdentityVerifier {
    private final GoogleAuthSettings settings;
    private final JwtDecoder decoder;
    @org.springframework.beans.factory.annotation.Autowired
    public GoogleIdentityVerifier(GoogleAuthSettings settings) {
        this(settings, jwksDecoder("https://www.googleapis.com/oauth2/v3/certs", 2000));
    }
    GoogleIdentityVerifier(GoogleAuthSettings settings, JwtDecoder decoder) {
        this.settings = settings;
        this.decoder = decoder;
    }
    // Package-private factory lets tests exercise the identical JWKS/algorithm/timeout path on loopback.
    // There is deliberately no runtime property accepting an arbitrary issuer/JWKS endpoint.
    static JwtDecoder jwksDecoder(String uri, int timeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        return NimbusJwtDecoder.withJwkSetUri(uri)
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(factory)).build();
    }
    public Identity verify(String token) {
        settings.requireEnabled();
        if (token == null || token.length() > 16384 || token.isBlank()) throw GoogleAuthFailure.rejected();
        try {
            Jwt jwt = decoder.decode(token);
            Instant now = Instant.now();
            String issuer = jwt.getClaimAsString("iss");
            String subject = jwt.getSubject();
            String nonce = jwt.getClaimAsString("nonce");
            String azp = jwt.getClaimAsString("azp");
            if (!Set.of("https://accounts.google.com", "accounts.google.com").contains(issuer)
                    || !jwt.getAudience().contains(settings.clientId())
                    || (jwt.getAudience().size() > 1 && !settings.clientId().equals(azp))
                    || (azp != null && !settings.clientId().equals(azp))
                    || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
                    || jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(now.plusSeconds(30))
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || (jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now))
                    || subject == null || !subject.matches("[A-Za-z0-9_-]{1,255}")
                    || nonce == null || !nonce.matches("[A-Za-z0-9_-]{43}")) {
                throw GoogleAuthFailure.rejected();
            }
            // No email or claimed role is returned to the account layer.
            return new Identity(subject, nonce);
        } catch (RuntimeException ignored) {
            throw GoogleAuthFailure.rejected();
        }
    }
    public record Identity(String subject, String nonce) { }
}
