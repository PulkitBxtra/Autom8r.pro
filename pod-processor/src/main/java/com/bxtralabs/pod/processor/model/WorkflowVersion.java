package com.bxtralabs.pod.processor.model;

import com.bxtralabs.pod.processor.model.graph.WorkflowGraph;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Read-only copy of pod-backend's WorkflowVersion; pod-backend owns the table.
// @Immutable so this pod can never write to it, and no unique constraint is
// declared here so ddl-auto=update never tries to touch the backend's schema.
@Entity
@Immutable
@Table(name = "workflow_version")
public class WorkflowVersion {

    @Id
    private String id;
    @Column(name = "workflow_id")
    private String workflowId;
    private Integer version;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private WorkflowGraph graph;
    private Long createdAt;

    public WorkflowVersion() {
    }

    public String getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public Integer getVersion() {
        return version;
    }

    public WorkflowGraph getGraph() {
        return graph;
    }

    public Long getCreatedAt() {
        return createdAt;
    }
}
