package com.bxtralabs.pod.webhooks.model;

import com.bxtralabs.pod.webhooks.common.IDs;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
public class ExecutionRun {

    @PrePersist
    public void prePersist() {
        id = IDs.generateID("exn");
    }

    @Id
    public String id;
    public String workflowId;
    public String status;
    public Long startTimestamp;
    public Long endTimestamp;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Map<String, Object> metadata;
    @OneToOne(targetEntity = ExecutionRunOutbox.class)
    public ExecutionRunOutbox executionRunOutbox;

    public ExecutionRun() {
    }

    public ExecutionRun(String id, String status, Long startTimestamp, Long endTimestamp, Map<String, Object> metadata, ExecutionRunOutbox executionRunOutbox) {
        this.id = id;
        this.status = status;
        this.startTimestamp = startTimestamp;
        this.endTimestamp = endTimestamp;
        this.metadata = metadata;
        this.executionRunOutbox = executionRunOutbox;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getStartTimestamp() {
        return startTimestamp;
    }

    public void setStartTimestamp(Long startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    public Long getEndTimestamp() {
        return endTimestamp;
    }

    public void setEndTimestamp(Long endTimestamp) {
        this.endTimestamp = endTimestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public ExecutionRunOutbox getExecutionRunOutbox() {
        return executionRunOutbox;
    }

    public void setExecutionRunOutbox(ExecutionRunOutbox executionRunOutbox) {
        this.executionRunOutbox = executionRunOutbox;
    }

    @Override
    public String toString() {
        return "ExecutionRun{" +
                "id='" + id + '\'' +
                ", status='" + status + '\'' +
                ", startTimestamp=" + startTimestamp +
                ", endTimestamp=" + endTimestamp +
                ", metadata=" + metadata +
                ", executionRunOutbox=" + executionRunOutbox +
                '}';
    }
}
