package com.pricetrack.exchange.auth.google;

import org.springframework.http.HttpStatus;

/** Public, fixed errors: never wrap a provider response or credential. */
public class GoogleAuthFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public GoogleAuthFailure(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static GoogleAuthFailure rejected() {
        return new GoogleAuthFailure(HttpStatus.UNAUTHORIZED, "GOOGLE_AUTH_REJECTED");
    }
}
