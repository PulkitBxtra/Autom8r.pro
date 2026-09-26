package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.repository.StepRunRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

// Runs one READY step: claim it, execute it, hand the result back to the Orchestrator.
// Execution happens outside any transaction, so a slow step never holds the run lock.
@Service
public class StepExecutor {

    @Autowired
    private StepRunRepository stepRunRepository;

    @Autowired
    private Orchestrator orchestrator;

    public void execute(String runId, String stepRunId) {
        if (stepRunRepository.claim(stepRunId, System.currentTimeMillis()) == 0) {
            return; // already claimed, or cancelled because the run failed
        }

        Map<String, Object> output = null;
        String error = null;
        try {
            output = runAction(stepRunId);
        } catch (Exception e) {
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        // If this throws (e.g. the DB is down) the step stays RUNNING; recovering stuck steps
        // is Phase 3 (a sweeper for RUNNING steps whose startedAt is too old).
        orchestrator.completeStep(runId, stepRunId, output, error);
    }

    // TODO(2.4): resolve the step's templates into its input and call the ActionHandler for
    // its app/type. Until then every step succeeds without doing anything, so the engine's
    // control flow can be exercised end to end.
    private Map<String, Object> runAction(String stepRunId) {
        return Map.of("simulated", true);
    }
}
