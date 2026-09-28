package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.StepRun;
import com.bxtralabs.pod.processor.model.StepTaskOutbox;
import com.bxtralabs.pod.processor.repository.ExecutionRunRepository;
import com.bxtralabs.pod.processor.repository.StepRunRepository;
import com.bxtralabs.pod.processor.repository.StepTaskOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

// Safety net for work that fell through the cracks. Everything it does is safe to repeat and
// safe to race with normal processing:
//
//  - RUNNING too long: the worker died mid-step, or its result was lost (e.g. the pod crashed
//    right after finishing, before Kafka acked the result). Reported as a temporary failure,
//    so the step retries (or fails the run if out of attempts) through the normal path. If the
//    worker was only slow, its late result is ignored because the attempt no longer matches.
//    The timeout must be longer than any step can legitimately take (http_request caps at 120s).
//
//  - READY too long: its step-tasks message was dropped (Kafka gave up redelivering it).
//    Queued again; if the original is still in flight, the worker's claim makes one a no-op.
//
//  - PENDING run with no steps: its workflow-events message was dropped. Started directly;
//    onRunStarted ignores runs that already started.
@Service
public class StuckWorkSweeper {

    static final int BATCH_SIZE = 100;

    private final StepRunRepository stepRunRepository;
    private final ExecutionRunRepository executionRunRepository;
    private final StepTaskOutboxRepository outboxRepository;
    private final Orchestrator orchestrator;
    private final TransactionTemplate transactionTemplate;
    private final long runningTimeoutMs;
    private final long readyTimeoutMs;
    private final long pendingRunTimeoutMs;

    public StuckWorkSweeper(StepRunRepository stepRunRepository, ExecutionRunRepository executionRunRepository,
                            StepTaskOutboxRepository outboxRepository, Orchestrator orchestrator,
                            TransactionTemplate transactionTemplate,
                            @Value("${steps.running-timeout-ms:300000}") long runningTimeoutMs,
                            @Value("${steps.ready-timeout-ms:120000}") long readyTimeoutMs,
                            @Value("${runs.pending-timeout-ms:120000}") long pendingRunTimeoutMs) {
        this.stepRunRepository = stepRunRepository;
        this.executionRunRepository = executionRunRepository;
        this.outboxRepository = outboxRepository;
        this.orchestrator = orchestrator;
        this.transactionTemplate = transactionTemplate;
        this.runningTimeoutMs = runningTimeoutMs;
        this.readyTimeoutMs = readyTimeoutMs;
        this.pendingRunTimeoutMs = pendingRunTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${steps.sweep-interval-ms:30000}")
    public void sweep() {
        long now = System.currentTimeMillis();
        timeOutStuckRunningSteps(now);
        requeueStaleReadySteps(now);
        startUnstartedRuns(now);
    }

    void timeOutStuckRunningSteps(long now) {
        List<StepRun> stuck = stepRunRepository.findStuckRunning(now - runningTimeoutMs, BATCH_SIZE);
        for (StepRun step : stuck) {
            System.out.println("Sweeper: step " + step.getId() + " RUNNING since " + step.getStartedAt()
                    + " with no result, treating attempt " + step.getAttempt() + " as failed");
            try {
                // The worker may have finished the call before dying: check before repeating it.
                orchestrator.completeStep(step.getRunId(), step.getId(), step.getInput(), null,
                        "No result within " + (runningTimeoutMs / 1000) + "s; the worker may have crashed",
                        true, step.getAttempt(), true);
            } catch (Exception e) {
                // One bad step shouldn't stop the rest; it'll be picked up next sweep.
                System.out.println("Sweeper: could not time out " + step.getId() + ": " + e);
            }
        }
    }

    void requeueStaleReadySteps(long now) {
        transactionTemplate.executeWithoutResult(tx -> {
            List<StepRun> stale = stepRunRepository.lockStaleReady(now - readyTimeoutMs, BATCH_SIZE);
            if (stale.isEmpty()) {
                return;
            }
            System.out.println("Sweeper: re-queueing " + stale.size() + " READY step(s) that were never picked up");
            for (StepRun step : stale) {
                step.setReadyAt(now);
            }
            stepRunRepository.saveAll(stale);
            outboxRepository.saveAll(stale.stream().map(s -> new StepTaskOutbox(s.getRunId(), s.getId())).toList());
        });
    }

    void startUnstartedRuns(long now) {
        for (String runId : executionRunRepository.findUnstartedRunIds(now - pendingRunTimeoutMs, BATCH_SIZE)) {
            System.out.println("Sweeper: run " + runId + " was never started, starting it");
            try {
                orchestrator.onRunStarted(runId);
            } catch (Exception e) {
                System.out.println("Sweeper: could not start " + runId + ": " + e);
            }
        }
    }
}
