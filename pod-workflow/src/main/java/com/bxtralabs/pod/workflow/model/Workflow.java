package com.bxtralabs.pod.webhooks.model;

import com.bxtralabs.pod.webhooks.common.IDs;
import jakarta.persistence.*;

import java.util.List;

@Entity
public class Workflow {

    @PrePersist
    public void prePersist() {
        if(id==null) {
            id = IDs.generateID("exc");
        }
    }

    @Id
    private String id;
    private String name;
    private String triggerId;
    @OneToMany
    @JoinColumn(name = "workflow_id")
    private List<Action> actions;

    public Workflow() {
    }

    public Workflow(String id, String name, String triggerId, List<Action> actions) {
        this.id = id;
        this.name = name;
        this.triggerId = triggerId;
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

    public List<Action> getActions() {
        return actions;
    }

    public void setActions(List<Action> actions) {
        this.actions = actions;
    }
}
