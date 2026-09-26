package com.bxtralabs.pod.processor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// Hands READY steps to the step pool, but only after the Orchestrator's transaction has
// committed -- otherwise a worker could try to claim a step whose READY status isn't
// visible yet. Phase 3 replaces this in-process pool with a step-tasks Kafka topic.
@Component
public class StepDispatcher {

    @Autowired
    private StepExecutor stepExecutor;

    @Autowired
    @Qualifier("stepPool")
    private TaskExecutor stepPool;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStepsReady(StepsReadyEvent event) {
        for (String stepRunId : event.stepRunIds()) {
            stepPool.execute(() -> stepExecutor.execute(event.runId(), stepRunId));
        }
    }
}
