package com.pricetrack.exchange.auth.google;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "google_auth_challenges")
public class GoogleAuthChallenge {
    @Id @Column(length = 36) String id;
    @Column(nullable = false, length = 5) String purpose;
    @Column(name = "nonce_hash", nullable = false, length = 64) String nonceHash;
    @Column(name = "binding_hash", nullable = false, length = 64) String bindingHash;
    @Column(name = "user_id") Long userId;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @Column(name = "consumed_at") Instant consumedAt;
    protected GoogleAuthChallenge() { }
}
