package com.bxtralabs.pod.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

// Read-only view of pod-connector's connection table: just enough to check that a workflow
// step's connection belongs to the user and to the step's app. Never selects the credentials.
// pod-connector owns the table's schema (see StepRunView for why @Subselect).
@Entity
@Immutable
@Subselect("select id, user_id, app_id from connection")
@Synchronize("connection")
public class ConnectionView {

    @Id
    private String id;
    @Column(name = "user_id")
    private String userId;
    @Column(name = "app_id")
    private String appId;

    public ConnectionView() {
    }

    public ConnectionView(String id, String userId, String appId) {
        this.id = id;
        this.userId = userId;
        this.appId = appId;
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
}
