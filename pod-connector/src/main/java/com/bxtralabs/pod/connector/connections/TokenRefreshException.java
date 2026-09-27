package com.bxtralabs.pod.connector.connections;

// A refresh failed for a reason that may pass (provider down, timeout, rate limit) and the
// current access token has already expired, so there's nothing usable to hand out right now.
// Callers should retry later.
public class TokenRefreshException extends RuntimeException {

    public TokenRefreshException(String message) {
        super(message);
    }
}
