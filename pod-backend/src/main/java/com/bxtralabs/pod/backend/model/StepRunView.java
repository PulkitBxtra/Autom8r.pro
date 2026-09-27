package com.bxtralabs.pod.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;
import org.hibernate.type.SqlTypes;

import java.util.Map;

// Read-only view of pod-processor's step_run table, for showing run progress in the UI.
// @Subselect (instead of mapping the table) means Hibernate treats this as a query and never
// creates or alters step_run, even though this pod runs ddl-auto=update. pod-processor owns
// that table's schema. status is a plain string so a status added there needs no change here.
@Entity
@Immutable
@Subselect("select * from step_run")
@Synchronize("step_run")
public class StepRunView {

    @Id
    private String id;
    @Column(name = "run_id")
    private String runId;
    @Column(name = "node_id")
    private String nodeId;
    private String status;
    private Integer attempt;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> input;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> output;
    private String error;
    private Long createdAt;
    private Long startedAt;
    private Long endedAt;
    private Long nextAttemptAt;

    public StepRunView() {
    }

    public String getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getStatus() {
        return status;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public String getError() {
        return error;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public Long getEndedAt() {
        return endedAt;
    }

    public Long getNextAttemptAt() {
        return nextAttemptAt;
    }
}
