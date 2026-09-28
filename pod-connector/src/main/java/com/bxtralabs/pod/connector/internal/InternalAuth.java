package com.bxtralabs.pod.connector.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// Guards the /internal endpoints, which other pods call (never browsers). They present the
// shared INTERNAL_API_TOKEN in the X-Internal-Token header. With no token configured the
// endpoints refuse everything, so a missing setting can't leave them open.
@Component
public class InternalAuth {

    public static final String HEADER = "X-Internal-Token";

    private final byte[] token;

    public InternalAuth(@Value("${internal.api-token:}") String token) {
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    public void require(String presented) {
        if (token.length < 16 || presented == null
                // Constant-time, so response timing can't reveal the token a byte at a time.
                || !MessageDigest.isEqual(token, presented.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new InternalAuthException();
        }
    }

    public static class InternalAuthException extends RuntimeException {
        public InternalAuthException() {
            super("Not allowed");
        }
    }
}
