package com.pricetrack.exchange.auth.google;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import java.security.KeyPairGenerator;
import java.security.interfaces.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;

/** Real RSA signatures, not a mock verifier or untrusted JWT decode. No network. */
class GoogleIdentityVerifierTest {
    private java.security.KeyPair keys;
    private GoogleIdentityVerifier verifier;
    private Map<String, Object> claims;
    @BeforeEach void setup() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        keys = generator.generateKeyPair();
        verifier = new GoogleIdentityVerifier(new GoogleAuthSettings(true, "test-client", "http://localhost:5173"),
                NimbusJwtDecoder.withPublicKey((RSAPublicKey)keys.getPublic()).build());
        claims = new HashMap<>();
        claims.put("iss", "https://accounts.google.com"); claims.put("sub", "12345678");
        claims.put("aud", "test-client"); claims.put("iat", Date.from(Instant.now().minusSeconds(5)));
        claims.put("exp", Date.from(Instant.now().plusSeconds(300)));
        claims.put("nonce", "n".repeat(43));
    }
    private String signed() throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder(); claims.forEach(builder::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey)keys.getPrivate())); return jwt.serialize();
    }
    @Test void verifiesRealSignatureAndReturnsOnlySubAndNonce() throws Exception {
        claims.put("email", "not-service-email@example.test"); claims.put("role", "ADMIN");
        assertThat(verifier.verify(signed())).isEqualTo(new GoogleIdentityVerifier.Identity("12345678", "n".repeat(43)));
    }
    @Test void rejectsForeignSignature() throws Exception {
        var original = keys;
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        keys = generator.generateKeyPair(); String token = signed(); keys = original;
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(GoogleAuthFailure.class);
    }
    @Test void rejectsWrongAudience() { claims.put("aud", "other"); rejected(); }
    @Test void rejectsWrongIssuer() { claims.put("iss", "https://attacker.test"); rejected(); }
    @Test void rejectsExpiredToken() { claims.put("exp", Date.from(Instant.now().minusSeconds(1))); rejected(); }
    @Test void rejectsFutureIssuedAt() { claims.put("iat", Date.from(Instant.now().plusSeconds(120))); rejected(); }
    @Test void rejectsFutureNotBefore() { claims.put("nbf", Date.from(Instant.now().plusSeconds(120))); rejected(); }
    @Test void rejectsMissingNonce() { claims.remove("nonce"); rejected(); }
    @Test void rejectsMissingSubject() { claims.remove("sub"); rejected(); }
    @Test void rejectsMissingExpiry() { claims.remove("exp"); rejected(); }
    @Test void rejectsMissingIssuedAt() { claims.remove("iat"); rejected(); }
    @Test void rejectsMultiAudienceWithoutAuthorizedParty() { claims.put("aud", List.of("test-client", "other")); rejected(); }
    @Test void rejectsDifferentAuthorizedParty() { claims.put("azp", "other"); rejected(); }
    @Test void acceptsOfficialBareIssuer() throws Exception { claims.put("iss", "accounts.google.com"); assertThat(verifier.verify(signed()).subject()).isEqualTo("12345678"); }
    @Test void acceptsExpectedAuthorizedParty() throws Exception { claims.put("aud", List.of("test-client", "other")); claims.put("azp", "test-client"); assertThat(verifier.verify(signed()).subject()).isEqualTo("12345678"); }
    @Test void rejectsUnsignedAndOversizedTokens() {
        assertThatThrownBy(() -> verifier.verify("a.b.c")).isInstanceOf(GoogleAuthFailure.class);
        assertThatThrownBy(() -> verifier.verify("a".repeat(16385))).isInstanceOf(GoogleAuthFailure.class);
    }
    private void rejected() {
        assertThatThrownBy(() -> verifier.verify(signed())).isInstanceOf(GoogleAuthFailure.class)
                .hasMessage("GOOGLE_AUTH_REJECTED");
    }
}
