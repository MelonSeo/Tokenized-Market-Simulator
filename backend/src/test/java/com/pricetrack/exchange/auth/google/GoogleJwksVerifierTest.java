package com.pricetrack.exchange.auth.google;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;

/** Real loopback HTTP JWKS exercises production's decoder factory without any public provider call. */
class GoogleJwksVerifierTest {
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicBoolean slow = new AtomicBoolean();
    private final AtomicReference<RSAKey> key = new AtomicReference<>();
    private String uri;
    @BeforeEach void setup() throws Exception {
        key.set(new RSAKeyGenerator(2048).keyID("first").generate());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newFixedThreadPool(2);server.setExecutor(executor);
        server.createContext("/jwks",exchange->{
            requests.incrementAndGet();
            try {
                if(slow.get())Thread.sleep(800);
                byte[] body=new JWKSet(key.get().toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);
            }catch(Exception ignored){/* no raw request/token logging */}finally{exchange.close();}
        });server.start();uri="http://127.0.0.1:"+server.getAddress().getPort()+"/jwks";
    }
    @AfterEach void close(){server.stop(0);executor.shutdownNow();}
    private GoogleIdentityVerifier verifier(boolean enabled) {
        return new GoogleIdentityVerifier(new GoogleAuthSettings(enabled,"test-client","http://localhost:5173"),
                GoogleIdentityVerifier.jwksDecoder(uri,150));
    }
    private JWTClaimsSet claims() {
        return new JWTClaimsSet.Builder().issuer("https://accounts.google.com").subject("verified-sub").audience("test-client")
                .issueTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("nonce","n".repeat(43)).build();
    }
    private String signed()throws Exception {
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.get().getKeyID()).build(),claims());
        jwt.sign(new RSASSASigner(key.get()));return jwt.serialize();
    }
    @Test void lazyFetchCacheAndNewKidRotation()throws Exception {
        var verifier=verifier(true);assertThat(requests.get()).isZero();
        assertThat(verifier.verify(signed()).subject()).isEqualTo("verified-sub");
        int initial=requests.get();verifier.verify(signed());assertThat(requests.get()).isEqualTo(initial);
        key.set(new RSAKeyGenerator(2048).keyID("rotated").generate());
        assertThat(verifier.verify(signed()).subject()).isEqualTo("verified-sub");assertThat(requests.get()).isGreaterThan(initial);
    }
    @Test void disabledNeverRequestsKeys()throws Exception {
        var verifier=verifier(false);String token=signed();
        assertThatThrownBy(()->verifier.verify(token)).isInstanceOf(GoogleAuthFailure.class);assertThat(requests.get()).isZero();
    }
    @Test void rejectsHmacAndUnsignedTokensWithoutTrustingHeader()throws Exception {
        var verifier=verifier(true);var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims());
        jwt.sign(new MACSigner(new byte[32]));String hmac=jwt.serialize();
        assertThatThrownBy(()->verifier.verify(hmac)).isInstanceOf(GoogleAuthFailure.class);
        assertThatThrownBy(()->verifier.verify(new PlainJWT(claims()).serialize())).isInstanceOf(GoogleAuthFailure.class);
        assertThat(requests.get()).isZero();
    }
    @Test void keyServerFailureFailsClosedWithoutRawResponse()throws Exception {
        status.set(503);var verifier=verifier(true);String token=signed();
        assertThatThrownBy(()->verifier.verify(token)).isInstanceOf(GoogleAuthFailure.class).hasMessage("GOOGLE_AUTH_REJECTED");
        assertThat(requests.get()).isPositive();
    }
    @Test void keyServerTimeoutIsBoundedAndFailsClosed()throws Exception {
        slow.set(true);var verifier=verifier(true);String token=signed();long start=System.nanoTime();
        assertThatThrownBy(()->verifier.verify(token)).isInstanceOf(GoogleAuthFailure.class).hasMessage("GOOGLE_AUTH_REJECTED");
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isLessThan(java.time.Duration.ofSeconds(2));
        assertThat(requests.get()).isPositive();
    }
}
