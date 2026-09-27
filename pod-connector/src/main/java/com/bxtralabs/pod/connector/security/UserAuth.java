package com.bxtralabs.pod.connector.security;

import com.bxtralabs.pod.connector.repository.RevokedTokenRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// Same rules as pod-backend's AuthService.requireUserId: a Bearer token that's validly signed,
// not expired, and not logged out.
@Component
public class UserAuth {

    private final JwtUtil jwtUtil;
    private final RevokedTokenRepository revokedTokens;

    public UserAuth(JwtUtil jwtUtil, RevokedTokenRepository revokedTokens) {
        this.jwtUtil = jwtUtil;
        this.revokedTokens = revokedTokens;
    }

    public String requireUserId(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing or invalid Authorization header");
        }
        String token = authorizationHeader.substring("Bearer ".length());
        // pod-backend's TokenBlacklist stores logged-out tokens as SHA-256 hex hashes.
        if (revokedTokens.existsById(sha256(token))) {
            throw new IllegalArgumentException("Token has been logged out");
        }
        return jwtUtil.extractUserId(token);
    }

    static String sha256(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
