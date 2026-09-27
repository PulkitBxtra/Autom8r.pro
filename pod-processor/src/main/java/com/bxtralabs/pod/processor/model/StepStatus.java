package com.bxtralabs.pod.processor.model;

// Lifecycle of one step within one run:
//   PENDING   -> waiting for parents (pendingDeps > 0)
//   READY     -> all parents done and at least one took an edge here; can be dispatched
//   RUNNING   -> claimed by a worker
//   RETRY_WAIT-> an attempt failed with a temporary error; READY again at nextAttemptAt
//   SUCCEEDED / FAILED / SKIPPED / CANCELLED are terminal.
// SKIPPED: every incoming edge was not taken, so the step never runs; its children
// treat it as finished-but-inactive. CANCELLED: the run failed before this step started.
public enum StepStatus {
    PENDING,
    READY,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED;
    }
}
