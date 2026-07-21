package com.bxtralabs.pod.workflow.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;

@Entity
public class ExecutionRunOutbox {

    @Id
    public String id;
    public String executionId;
    @OneToOne(targetEntity = ExecutionRun.class)
    public ExecutionRun executionRun;

    public ExecutionRunOutbox() {
    }

    public ExecutionRunOutbox(String id, String executionId, ExecutionRun executionRun) {
        this.id = id;
        this.executionId = executionId;
        this.executionRun = executionRun;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public ExecutionRun getExecutionRun() {
        return executionRun;
    }

    public void setExecutionRun(ExecutionRun executionRun) {
        this.executionRun = executionRun;
    }

    @Override
    public String toString() {
        return "ExecutionRunOutbox{" +
                "id='" + id + '\'' +
                ", executionId='" + executionId + '\'' +
                ", executionRun=" + executionRun +
                '}';
    }
}
