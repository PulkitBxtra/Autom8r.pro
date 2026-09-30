package com.bxtralabs.pod.webhooks.model;

import jakarta.persistence.*;

// pod-backend owns workflows; this pod only reads one to start a run of its current version.
@Entity
@Table(name = "workflow")
public class Workflow {

    @Id
    private String id;
    private String name;
    private String userId;
    // Read here to pin each run to the graph that was live when it triggered.
    private String currentVersionId;

    public Workflow() {
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getUserId() {
        return userId;
    }

    public String getCurrentVersionId() {
        return currentVersionId;
    }
}
