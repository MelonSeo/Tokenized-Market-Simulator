package com.pricetrack.exchange.auth.google;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.pricetrack.exchange.auth.AuthController.TokenResponse;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@RestController
@RequestMapping("/api")
public class GoogleAuthController {
    private static final String COOKIE = "tms_google_binding";
    private final GoogleAuthSettings settings;
    private final GoogleAccountService accounts;
    // Bounded per-process abuse guard; challenge consumption itself is PostgreSQL atomic.
    private final Map<String, Window> attempts = new LinkedHashMap<>();
    public GoogleAuthController(GoogleAuthSettings settings, GoogleAccountService accounts) {
        this.settings = settings; this.accounts = accounts;
    }
    @ModelAttribute
    public void preventCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
    @GetMapping("/auth/google/config")
    public Config config() { return new Config(settings.enabled(), settings.enabled() ? settings.clientId() : null); }
    @PostMapping("/auth/google/challenges")
    public GoogleAccountService.ChallengeResponse loginChallenge(HttpServletRequest request, HttpServletResponse response) {
        guard(request);
        return accounts.issue(binding(request, response), null);
    }
    @PostMapping("/me/google/challenges")
    public GoogleAccountService.ChallengeResponse linkChallenge(@AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request, HttpServletResponse response) {
        guard(request);
        return accounts.issue(binding(request, response), user.userId());
    }
    @PostMapping("/auth/google/login")
    public TokenResponse login(@Valid @RequestBody Proof proof, HttpServletRequest request) {
        guard(request);
        return accounts.login(proof.challengeId(), cookie(request), proof.credential());
    }
    @PostMapping("/me/google/link")
    public ResponseEntity<Void> link(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody LinkProof proof, HttpServletRequest request) {
        guard(request);
        accounts.link(user.userId(), proof.challengeId(), cookie(request), proof.credential(), proof.password());
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/me/auth-methods")
    public GoogleAccountService.AuthMethods methods(@AuthenticationPrincipal AuthenticatedUser user) {
        return accounts.methods(user.userId());
    }
    private void guard(HttpServletRequest request) {
        settings.requireEnabled();
        if (!settings.permits(request.getHeader("Origin"))
                || !"1".equals(request.getHeader("X-Google-Auth"))
                || request.getContentType() == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType())))
            throw new GoogleAuthFailure(HttpStatus.FORBIDDEN, "GOOGLE_AUTH_ORIGIN_REJECTED");
        rateLimit(request.getRemoteAddr()); // Do not trust an arbitrary forwarded IP header.
    }
    private synchronized void rateLimit(String address) {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(entry -> entry.getValue().until < now);
        Window window = attempts.get(address);
        if (window == null) {
            if (attempts.size() >= 4096) throw limited();
            window = new Window(now + 300_000); attempts.put(address, window);
        }
        if (++window.count > 40) throw limited();
    }
    private GoogleAuthFailure limited() {
        return new GoogleAuthFailure(HttpStatus.TOO_MANY_REQUESTS, "GOOGLE_AUTH_RATE_LIMITED");
    }
    private String cookie(HttpServletRequest request) {
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName()) && cookie.getValue().matches("[A-Za-z0-9_-]{43}")) return cookie.getValue();
        }
        return null;
    }
    private String binding(HttpServletRequest request, HttpServletResponse response) {
        String value = cookie(request);
        if (value == null) value = GoogleAccountService.random();
        // guard() already matched an exact configured origin. Its scheme describes browser TLS,
        // including HTTPS terminated by a same-origin reverse proxy; never trust arbitrary X-Forwarded-*.
        boolean secure = "https".equals(java.net.URI.create(request.getHeader("Origin")).getScheme());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, value)
                .httpOnly(true).secure(secure).sameSite("Strict").path("/api").maxAge(1800).build().toString());
        return value;
    }
    private static class Window { final long until; int count; Window(long until) { this.until = until; } }
    public record Config(boolean enabled, String clientId) { }
    public record Proof(@NotBlank @Size(max=36) String challengeId,
            @NotBlank @Size(max=16384) String credential) { }
    public record LinkProof(@NotBlank @Size(max=36) String challengeId,
            @NotBlank @Size(max=16384) String credential, @NotBlank @Size(max=72) String password) { }
}
