package com.bxtralabs.pod.backend.security;

import com.bxtralabs.pod.backend.model.RevokedToken;
import com.bxtralabs.pod.backend.repository.RevokedTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

// DB-backed so a revocation survives restarts and is shared across
// pod-backend instances -- the old in-memory Set was neither.
@Component
public class TokenBlacklist {

    @Autowired
    private RevokedTokenRepository revokedTokenRepository;

    public void revoke(String token) {
        String hash = hash(token);
        if (!revokedTokenRepository.existsById(hash)) {
            revokedTokenRepository.save(new RevokedToken(hash, Instant.now().toEpochMilli()));
        }
    }

    public boolean isRevoked(String token) {
        return revokedTokenRepository.existsById(hash(token));
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
