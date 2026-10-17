package com.pricetrack.exchange.auth.google;

import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.Date;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** In-memory fixture issuer with actual RSA signatures. Never enabled in a product profile. */
@TestConfiguration
public class GoogleTestIdentity {
    private static final KeyPair KEYS;
    static {
        try { KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); KEYS = generator.generateKeyPair(); }
        catch (GeneralSecurityException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @Bean @Primary GoogleIdentityVerifier fixtureVerifier(GoogleAuthSettings settings) {
        return new GoogleIdentityVerifier(settings, NimbusJwtDecoder.withPublicKey((RSAPublicKey)KEYS.getPublic()).build());
    }
    public static String token(String subject, String nonce) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("https://accounts.google.com")
                    .subject(subject).audience("offline-google-client").issueTime(Date.from(Instant.now().minusSeconds(1)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("nonce", nonce)
                    .claim("email", "same-email@example.test").claim("email_verified", true).claim("role", "ADMIN").build();
            SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            token.sign(new RSASSASigner((RSAPrivateKey)KEYS.getPrivate())); return token.serialize();
        } catch (JOSEException failure) { throw new IllegalStateException("Fixture signing failed"); }
    }
}
