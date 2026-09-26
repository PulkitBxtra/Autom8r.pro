package com.bxtralabs.pod.processor.model;

import com.bxtralabs.pod.processor.common.IDs;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

// One step of one ExecutionRun. Owned by pod-processor.
// Unique on (run_id, node_id): if workflow-events is redelivered, re-creating the
// steps for the same run fails instead of starting the run twice.
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"run_id", "node_id"}))
public class StepRun {

    @PrePersist
    public void prePersist() {
        if(id==null) {
            id = IDs.generateID("stp");
        }
        if(createdAt==null) {
            createdAt = System.currentTimeMillis();
        }
    }

    @Id
    private String id;
    @Column(name = "run_id", nullable = false)
    private String runId;
    // The GraphNode.id this row executes.
    @Column(name = "node_id", nullable = false)
    private String nodeId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepStatus status;
    // Parents that haven't finished yet. The step can be decided once this hits 0.
    @Column(nullable = false)
    private Integer pendingDeps;
    // Parents that finished AND whose edge to this step was taken. If this is still 0
    // when pendingDeps hits 0, no path reached this step, so it's SKIPPED instead of run.
    @Column(nullable = false)
    private Integer activeParents;
    @Column(nullable = false)
    private Integer attempt;
    // Parameters with templates resolved, frozen when the step is dispatched.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> input;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> output;
    @Column(columnDefinition = "text")
    private String error;
    private Long createdAt;
    private Long startedAt;
    private Long endedAt;

    public StepRun() {
    }

    public StepRun(String runId, String nodeId, int pendingDeps) {
        this.runId = runId;
        this.nodeId = nodeId;
        this.pendingDeps = pendingDeps;
        this.activeParents = 0;
        this.attempt = 0;
        this.status = StepStatus.PENDING;
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

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public StepStatus getStatus() {
        return status;
    }

    public void setStatus(StepStatus status) {
        this.status = status;
    }

    public Integer getPendingDeps() {
        return pendingDeps;
    }

    public void setPendingDeps(Integer pendingDeps) {
        this.pendingDeps = pendingDeps;
    }

    public Integer getActiveParents() {
        return activeParents;
    }

    public void setActiveParents(Integer activeParents) {
        this.activeParents = activeParents;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = output;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Long endedAt) {
        this.endedAt = endedAt;
    }

    @Override
    public String toString() {
        return "StepRun{" +
                "id='" + id + '\'' +
                ", runId='" + runId + '\'' +
                ", nodeId='" + nodeId + '\'' +
                ", status=" + status +
                ", pendingDeps=" + pendingDeps +
                ", activeParents=" + activeParents +
                ", attempt=" + attempt +
                '}';
    }
}
