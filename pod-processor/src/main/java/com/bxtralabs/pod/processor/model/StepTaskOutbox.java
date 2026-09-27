package com.bxtralabs.pod.processor.model;

import com.bxtralabs.pod.processor.common.IDs;
import jakarta.persistence.*;

// A READY step waiting to be published to the step-tasks topic. Written in the same
// transaction that marks the step READY, so a step can never be READY without being
// queued for dispatch (and never dispatched before its READY status is committed).
@Entity
public class StepTaskOutbox {

    @PrePersist
    public void prePersist() {
        if(id==null) {
            id = IDs.generateID("sto");
        }
        if(createdAt==null) {
            createdAt = System.currentTimeMillis();
        }
    }

    @Id
    private String id;
    @Column(name = "run_id", nullable = false)
    private String runId;
    @Column(name = "step_run_id", nullable = false)
    private String stepRunId;
    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    public StepTaskOutbox() {
    }

    public StepTaskOutbox(String runId, String stepRunId) {
        this.runId = runId;
        this.stepRunId = stepRunId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getStepRunId() {
        return stepRunId;
    }

    public void setStepRunId(String stepRunId) {
        this.stepRunId = stepRunId;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
