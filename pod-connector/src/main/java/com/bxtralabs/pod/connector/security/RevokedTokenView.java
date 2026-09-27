package com.bxtralabs.pod.connector.security;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

// Read-only view of pod-backend's revoked_token table (logged-out tokens, stored as SHA-256
// hashes), so logging out in pod-backend also cuts off access here. @Subselect makes Hibernate
// treat this as a query, so this pod's ddl-auto=update can never create or alter the table.
@Entity
@Immutable
@Subselect("select token_hash, revoked_at from revoked_token")
@Synchronize("revoked_token")
public class RevokedTokenView {

    @Id
    private String tokenHash;
    private Long revokedAt;

    public String getTokenHash() {
        return tokenHash;
    }

    public Long getRevokedAt() {
        return revokedAt;
    }
}
