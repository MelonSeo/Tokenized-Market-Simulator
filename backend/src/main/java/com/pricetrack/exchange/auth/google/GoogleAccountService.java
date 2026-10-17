package com.pricetrack.exchange.auth.google;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.pricetrack.exchange.auth.AuthController.TokenResponse;
import com.pricetrack.exchange.auth.AuthService;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.wallet.WalletService;

/** Authentication proof first, then short atomic challenge/account transaction. No asset transfer. */
@Service
public class GoogleAccountService {
    private final GoogleAuthSettings settings;
    private final GoogleIdentityVerifier verifier;
    private final GoogleAuthChallengeRepository challenges;
    private final UserRepository users;
    private final WalletService wallets;
    private final PasswordEncoder passwords;
    private final AuthService auth;
    private final TransactionTemplate tx;
    private static final SecureRandom RANDOM = new SecureRandom();

    public GoogleAccountService(GoogleAuthSettings settings, GoogleIdentityVerifier verifier,
            GoogleAuthChallengeRepository challenges, UserRepository users, WalletService wallets,
            PasswordEncoder passwords, AuthService auth, PlatformTransactionManager manager) {
        this.settings = settings; this.verifier = verifier; this.challenges = challenges;
        this.users = users; this.wallets = wallets; this.passwords = passwords; this.auth = auth;
        this.tx = new TransactionTemplate(manager);
    }
    public static String random() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String hash(String value) {
        if (value == null) throw GoogleAuthFailure.rejected();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public ChallengeResponse issue(String binding, Long userId) {
        settings.requireEnabled();
        return tx.execute(status -> {
            Instant now = Instant.now();
            if (challenges.countByBindingHashAndCreatedAtAfter(hash(binding), now.minusSeconds(300)) >= 8)
                throw new GoogleAuthFailure(HttpStatus.TOO_MANY_REQUESTS, "GOOGLE_AUTH_RATE_LIMITED");
            if (userId != null && !users.findById(userId).map(User::supportsLocalLogin).orElse(false))
                throw GoogleAuthFailure.rejected();
            String nonce = random();
            GoogleAuthChallenge c = new GoogleAuthChallenge();
            c.id = UUID.randomUUID().toString(); c.purpose = userId == null ? "LOGIN" : "LINK";
            c.nonceHash = hash(nonce); c.bindingHash = hash(binding); c.userId = userId;
            c.createdAt = now; c.expiresAt = now.plusSeconds(300);
            challenges.save(c);
            return new ChallengeResponse(c.id, nonce, c.purpose, c.expiresAt);
        });
    }
    public TokenResponse login(String id, String binding, String credential) {
        GoogleIdentityVerifier.Identity identity = verifier.verify(credential); // no DB locks / external I/O overlap
        try {
            return completeLogin(id, binding, identity);
        } catch (DataIntegrityViolationException collision) {
            // Only a known google_sub uniqueness race can be retried in a NEW transaction.
            if (!googleSubCollision(collision)) throw collision;
            return completeLogin(id, binding, identity);
        }
    }
    private TokenResponse completeLogin(String id, String binding, GoogleIdentityVerifier.Identity identity) {
        return tx.execute(status -> {
            GoogleAuthChallenge c = requireChallenge(id, binding, identity.nonce(), null);
            User user = users.findByGoogleSub(identity.subject()).orElseGet(() -> {
                User created = new User();
                created.setGoogleSub(identity.subject()); created.setNickname("Google 사용자");
                created.setRole(UserRole.USER);
                created = users.saveAndFlush(created);
                wallets.initializeBalances(created.getId());
                return created;
            });
            c.consumedAt = Instant.now();
            return auth.token(user);
        }); // JWT is returned only after COMMIT succeeds.
    }
    public void link(Long userId, String id, String binding, String credential, String password) {
        GoogleIdentityVerifier.Identity identity = verifier.verify(credential);
        // BCrypt before holding a row lock; recheck unchanged hash inside the transaction.
        User candidate = users.findById(userId).orElseThrow(GoogleAuthFailure::rejected);
        String expectedHash = candidate.getPasswordHash();
        if (!candidate.supportsLocalLogin() || password == null || password.length() > 72
                || !passwords.matches(password, expectedHash)) throw GoogleAuthFailure.rejected();
        try {
            tx.executeWithoutResult(status -> {
                GoogleAuthChallenge c = requireChallenge(id, binding, identity.nonce(), userId);
                User user = users.findForGoogleLink(userId).orElseThrow(GoogleAuthFailure::rejected);
                if (!user.supportsLocalLogin() || !Objects.equals(expectedHash, user.getPasswordHash()))
                    throw GoogleAuthFailure.rejected();
                User owner = users.findByGoogleSub(identity.subject()).orElse(null);
                if ((owner != null && !owner.getId().equals(userId))
                        || (user.getGoogleSub() != null && !user.getGoogleSub().equals(identity.subject())))
                    throw conflict();
                user.setGoogleSub(identity.subject());
                users.flush();
                c.consumedAt = Instant.now();
            });
        } catch (DataIntegrityViolationException collision) {
            if (googleSubCollision(collision)) throw conflict();
            throw collision;
        }
    }
    private GoogleAuthChallenge requireChallenge(String id, String binding, String nonce, Long userId) {
        if (id == null || id.length() > 36) throw GoogleAuthFailure.rejected();
        GoogleAuthChallenge c = challenges.findLocked(id).orElseThrow(GoogleAuthFailure::rejected);
        if (c.consumedAt != null || !c.expiresAt.isAfter(Instant.now())
                || !c.bindingHash.equals(hash(binding)) || !c.nonceHash.equals(hash(nonce))
                || !Objects.equals(c.userId, userId)
                || !c.purpose.equals(userId == null ? "LOGIN" : "LINK")) throw GoogleAuthFailure.rejected();
        return c;
    }
    private boolean googleSubCollision(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return name != null && (name.contains("google_sub") || name.equals("uk_users_google_sub"));
            }
        }
        return false;
    }
    private GoogleAuthFailure conflict() {
        return new GoogleAuthFailure(HttpStatus.CONFLICT, "GOOGLE_ACCOUNT_ALREADY_LINKED");
    }
    public AuthMethods methods(Long userId) {
        User user = users.findById(userId).orElseThrow(GoogleAuthFailure::rejected);
        return new AuthMethods(user.supportsLocalLogin(), user.supportsGoogleLogin());
    }
    public record ChallengeResponse(String challengeId, String nonce, String purpose, Instant expiresAt) { }
    public record AuthMethods(boolean local, boolean google) { }
}
