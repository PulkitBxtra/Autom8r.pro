package com.bxtralabs.pod.backend.model;

import com.bxtralabs.pod.backend.common.IDs;
import jakarta.persistence.*;

import java.util.List;

@Entity
public class Workflow {

    @PrePersist
    public void prePersist() {
        if(id==null) {
            id = IDs.generateID("wfl");
        }
    }

    @Id
    private String id;
    private String name;
    private String triggerId;
    private String userId;
    // Latest WorkflowVersion; new runs execute this graph.
    private String currentVersionId;
    @OneToMany
    @JoinColumn(name = "workflow_id")
    private List<Action> actions;

    public Workflow() {
    }

    public Workflow(String id, String name, String triggerId, String userId, List<Action> actions) {
        this.id = id;
        this.name = name;
        this.triggerId = triggerId;
        this.userId = userId;
        this.actions = actions;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTriggerId() {
        return triggerId;
    }

    public void setTriggerId(String triggerId) {
        this.triggerId = triggerId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCurrentVersionId() {
        return currentVersionId;
    }

    public void setCurrentVersionId(String currentVersionId) {
        this.currentVersionId = currentVersionId;
    }

    public List<Action> getActions() {
        return actions;
    }

    public void setActions(List<Action> actions) {
        this.actions = actions;
    }
}
