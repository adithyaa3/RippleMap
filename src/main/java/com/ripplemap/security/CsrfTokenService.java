package com.ripplemap.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Holds a random per-process token. Pages embed it in a meta tag; the browser client sends it back in the
 * {@value #HEADER} header on every state-changing request. A cross-site page can neither read the token nor send
 * a custom header without a CORS preflight (which is never granted), so forged requests are rejected.
 */
@Component
public class CsrfTokenService {

    public static final String HEADER = "X-RippleMap-CSRF";

    private final String token;

    public CsrfTokenService() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String token() {
        return token;
    }

    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), candidate.getBytes(StandardCharsets.UTF_8));
    }
}
