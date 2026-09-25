package com.bxtralabs.pod.backend.model;

import com.bxtralabs.pod.backend.common.IDs;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// An immutable snapshot of a workflow's graph. Every save creates a new row, and
// each ExecutionRun pins the version it started on, so editing a workflow never
// changes runs that are already in flight.
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"workflow_id", "version"}))
public class WorkflowVersion {

    @PrePersist
    public void prePersist() {
        if(id==null) {
            id = IDs.generateID("wfv");
        }
        if(createdAt==null) {
            createdAt = System.currentTimeMillis();
        }
    }

    @Id
    private String id;
    @Column(name = "workflow_id", nullable = false)
    private String workflowId;
    // 1, 2, 3... per workflow.
    @Column(nullable = false)
    private Integer version;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private WorkflowGraph graph;
    private Long createdAt;

    public WorkflowVersion() {
    }

    public WorkflowVersion(String workflowId, Integer version, WorkflowGraph graph) {
        this.workflowId = workflowId;
        this.version = version;
        this.graph = graph;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public WorkflowGraph getGraph() {
        return graph;
    }

    public void setGraph(WorkflowGraph graph) {
        this.graph = graph;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "WorkflowVersion{" +
                "id='" + id + '\'' +
                ", workflowId='" + workflowId + '\'' +
                ", version=" + version +
                ", createdAt=" + createdAt +
                '}';
    }
}
