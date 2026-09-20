package com.bxtralabs.pod.backend.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class RevokedToken {

    // SHA-256 hash of the bearer token, not the raw token -- no reason to
    // keep a live credential sitting in the DB in plaintext.
    @Id
    private String tokenHash;
    private Long revokedAt;

    public RevokedToken() {
    }

    public RevokedToken(String tokenHash, Long revokedAt) {
        this.tokenHash = tokenHash;
        this.revokedAt = revokedAt;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Long getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Long revokedAt) {
        this.revokedAt = revokedAt;
    }
}
