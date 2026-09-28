package com.bxtralabs.pod.connector.model;

import com.bxtralabs.pod.connector.common.IDs;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

// An active workflow's app trigger, registered with the app so it calls us when the event happens
// (e.g. a GitHub repository webhook for "New Issue"). One per workflow; replaced when the workflow
// is saved again while on, removed when it's turned off. secret signs the app's deliveries and is
// stored encrypted.
@Entity
@Table(indexes = @Index(name = "idx_trigger_subscription_workflow", columnList = "workflowId", unique = true))
public class TriggerSubscription {

    public static final String STATUS_ACTIVE = "ACTIVE";
    // Registering with the app failed; lastError says why. Nothing is listening.
    public static final String STATUS_ERROR = "ERROR";

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = IDs.generateID("tsub");
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
    private String workflowId;
    @Column(nullable = false)
    private String userId;
    @Column(nullable = false)
    private String appId;
    // Catalog trigger id, e.g. trg_github_new_issue.
    @Column(nullable = false)
    private String triggerId;
    private String connectionId;
    // The trigger's settings (e.g. {"repository": "octo/app"}).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config;
    @Column(nullable = false)
    private String status;
    // Encrypted (CredentialCipher), e.g. the GitHub webhook secret.
    @Column(columnDefinition = "text")
    private String secret;
    // The app's id for what we registered (e.g. the GitHub hook id), to remove it later.
    private String externalId;
    @Column(columnDefinition = "text")
    private String lastError;
    private Long lastEventAt;
    private Long createdAt;
    private Long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getTriggerId() { return triggerId; }
    public void setTriggerId(String triggerId) { this.triggerId = triggerId; }
    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Long getLastEventAt() { return lastEventAt; }
    public void setLastEventAt(Long lastEventAt) { this.lastEventAt = lastEventAt; }
    public Long getCreatedAt() { return createdAt; }
    public Long getUpdatedAt() { return updatedAt; }

    // Leaves out the secret.
    @Override
    public String toString() {
        return "TriggerSubscription{" + id + ", workflow=" + workflowId + ", " + appId + "/" + triggerId + ", " + status + "}";
    }
}
