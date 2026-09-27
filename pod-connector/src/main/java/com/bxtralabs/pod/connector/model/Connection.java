package com.bxtralabs.pod.connector.model;

import com.bxtralabs.pod.connector.common.IDs;
import jakarta.persistence.*;

// A user's saved credentials for one app account (a GitHub user, a Slack workspace...).
// credentials holds CredentialCipher output and never leaves this pod; every other field is
// safe to show in the UI. Workflow steps will reference a connection by id, never the secret.
@Entity
@Table(indexes = {
        @Index(name = "idx_connection_user_app", columnList = "userId, appId"),
        // TokenRefreshScheduler looks for active connections by expiry.
        @Index(name = "idx_connection_status_expires", columnList = "status, expiresAt")})
public class Connection {

    public static final String AUTH_TOKEN = "TOKEN";
    public static final String AUTH_OAUTH = "OAUTH";

    public static final String STATUS_ACTIVE = "ACTIVE";
    // The provider stopped accepting the credentials; the user has to reconnect.
    public static final String STATUS_NEEDS_REAUTH = "NEEDS_REAUTH";

    @PrePersist
    public void prePersist() {
        if(id==null) {
            id = IDs.generateID("con");
        }
        long now = System.currentTimeMillis();
        if(createdAt==null) {
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
    // Catalog app id: app_github, app_slack, ...
    @Column(nullable = false)
    private String appId;
    // Which account this is, from the provider: "@octocat", "Acme · @autom8r-bot".
    private String label;
    // AUTH_TOKEN or AUTH_OAUTH.
    @Column(nullable = false)
    private String authType;
    // STATUS_ACTIVE or STATUS_NEEDS_REAUTH.
    @Column(nullable = false)
    private String status;
    // OAuth scopes granted, space-separated. Null for token connections.
    private String scopes;
    // Encrypted ("v1:..."), see CredentialCipher.
    @Column(columnDefinition = "text", nullable = false)
    private String credentials;
    // Access token expiry for OAuth providers that issue expiring tokens. A plain column (not
    // inside credentials) so the refresh job can find expiring tokens without decrypting.
    private Long expiresAt;
    private Long lastRefreshedAt;
    // Consecutive temporary refresh failures, and when to try again (backs off).
    private Integer refreshFailures;
    private Long nextRefreshAt;
    @Column(columnDefinition = "text")
    private String lastError;
    private Long createdAt;
    private Long updatedAt;
    private Long lastUsedAt;

    public Connection() {
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

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public String getCredentials() {
        return credentials;
    }

    public void setCredentials(String credentials) {
        this.credentials = credentials;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getLastRefreshedAt() {
        return lastRefreshedAt;
    }

    public void setLastRefreshedAt(Long lastRefreshedAt) {
        this.lastRefreshedAt = lastRefreshedAt;
    }

    public int getRefreshFailures() {
        return refreshFailures == null ? 0 : refreshFailures;
    }

    public void setRefreshFailures(Integer refreshFailures) {
        this.refreshFailures = refreshFailures;
    }

    public Long getNextRefreshAt() {
        return nextRefreshAt;
    }

    public void setNextRefreshAt(Long nextRefreshAt) {
        this.nextRefreshAt = nextRefreshAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
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

    public Long getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Long lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    // Deliberately leaves out credentials so a stray log line can't leak them, even encrypted.
    @Override
    public String toString() {
        return "Connection{" +
                "id='" + id + '\'' +
                ", userId='" + userId + '\'' +
                ", appId='" + appId + '\'' +
                ", label='" + label + '\'' +
                ", authType='" + authType + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
