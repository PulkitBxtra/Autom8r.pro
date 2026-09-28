package com.bxtralabs.pod.connector.model;

import com.bxtralabs.pod.connector.common.IDs;
import jakarta.persistence.*;

// A user's own OAuth app registered with a provider (e.g. a GitHub App they created), used
// instead of the server's app when they sign in. Several connections can share one: fixing the
// secret here fixes all of them. clientSecret holds CredentialCipher output and never leaves
// this pod; the client id is not a secret and is shown in the UI.
@Entity
@Table(name = "oauth_client", indexes = @Index(name = "idx_oauth_client_user_provider", columnList = "userId, provider"))
public class OAuthClient {

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = IDs.generateID("oac");
        }
        long now = System.currentTimeMillis();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = System.currentTimeMillis();
    }

    @Id
    private String id;
    @Column(nullable = false)
    private String userId;
    // OAuthProviders id: github, google.
    @Column(nullable = false)
    private String provider;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String clientId;
    // Encrypted ("v1:..."), see CredentialCipher.
    @Column(columnDefinition = "text", nullable = false)
    private String clientSecret;
    private Long createdAt;
    private Long updatedAt;

    public OAuthClient() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Leaves out the secret so a stray log line can't leak it, even encrypted.
    @Override
    public String toString() {
        return "OAuthClient{id='" + id + "', userId='" + userId + "', provider='" + provider + "', name='" + name + "'}";
    }
}
