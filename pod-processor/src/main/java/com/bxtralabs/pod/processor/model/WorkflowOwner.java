package com.bxtralabs.pod.processor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

// Read-only view of who owns a workflow (pod-backend's workflow table), so a step's connection
// can be checked against the owner when fetching its credentials. @Subselect: this pod never
// creates or alters the table.
@Entity
@Immutable
@Subselect("select id, user_id from workflow")
@Synchronize("workflow")
public class WorkflowOwner {

    @Id
    private String id;
    @Column(name = "user_id")
    private String userId;

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }
}
