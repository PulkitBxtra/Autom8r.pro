package com.bxtralabs.pod.connector.model;

import jakarta.persistence.*;

// One in-progress OAuth sign-in. The id is the random `state` sent to the provider: the callback
// must bring it back, which proves the callback belongs to a sign-in this user started (CSRF),
// and tells us which user/app it's for (the callback carries no login token). Single use, and
// only valid for a few minutes. The PKCE verifier is stored encrypted.
@Entity
@Table(name = "oauth_state")
public class OAuthState {

    @Id
    private String id;
    @Column(nullable = false)
    private String userId;
    @Column(nullable = false)
    private String appId;
    @Column(nullable = false)
    private String provider;
    // Set when this sign-in is reconnecting an existing connection.
    private String connectionId;
    @Column(columnDefinition = "text", nullable = false)
    private String codeVerifier;
    @Column(nullable = false)
    private Long createdAt;

    public OAuthState() {
    }

    public OAuthState(String id, String userId, String appId, String provider, String connectionId,
                      String codeVerifier, Long createdAt) {
        this.id = id;
        this.userId = userId;
        this.appId = appId;
        this.provider = provider;
        this.connectionId = connectionId;
        this.codeVerifier = codeVerifier;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getAppId() {
        return appId;
    }

    public String getProvider() {
        return provider;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public String getCodeVerifier() {
        return codeVerifier;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "OAuthState{userId='" + userId + "', appId='" + appId + "', provider='" + provider + "'}";
    }
}
