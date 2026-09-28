package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.StepRun;
import com.bxtralabs.pod.processor.model.StepStatus;
import com.bxtralabs.pod.processor.model.StepTaskOutbox;
import com.bxtralabs.pod.processor.repository.ExecutionRunRepository;
import com.bxtralabs.pod.processor.repository.StepRunRepository;
import com.bxtralabs.pod.processor.repository.StepTaskOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StuckWorkSweeperTest {

    private final StepRunRepository stepRuns = mock(StepRunRepository.class);
    private final ExecutionRunRepository runs = mock(ExecutionRunRepository.class);
    private final StepTaskOutboxRepository outbox = mock(StepTaskOutboxRepository.class);
    private final Orchestrator orchestrator = mock(Orchestrator.class);
    // running timeout 300s, ready timeout 120s, pending-run timeout 120s
    private final StuckWorkSweeper sweeper = new StuckWorkSweeper(stepRuns, runs, outbox, orchestrator,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), 300_000, 120_000, 120_000);

    private static final long NOW = 10_000_000;

    @BeforeEach
    void nothingByDefault() {
        when(stepRuns.findStuckRunning(anyLong(), anyInt())).thenReturn(List.of());
        when(stepRuns.lockStaleReady(anyLong(), anyInt())).thenReturn(List.of());
        when(runs.findUnstartedRunIds(anyLong(), anyInt())).thenReturn(List.of());
    }

    private static StepRun step(String id, StepStatus status, int attempt) {
        StepRun s = new StepRun("exn_1", "node_" + id, 0);
        s.setId(id);
        s.setStatus(status);
        s.setAttempt(attempt);
        return s;
    }

    @Test
    void usesConfiguredCutoffs() {
        sweeper.sweep();
        verify(stepRuns).findStuckRunning(longThat(c -> c <= System.currentTimeMillis() - 300_000), anyInt());
        verify(stepRuns).lockStaleReady(longThat(c -> c <= System.currentTimeMillis() - 120_000), anyInt());
        verify(runs).findUnstartedRunIds(longThat(c -> c <= System.currentTimeMillis() - 120_000), anyInt());
    }

    @Test
    void stuckRunningStepIsReportedAsATemporaryFailureForItsAttempt() {
        StepRun s = step("stp_a", StepStatus.RUNNING, 2);
        s.setInput(Map.of("url", "http://x"));
        when(stepRuns.findStuckRunning(anyLong(), anyInt())).thenReturn(List.of(s));

        sweeper.timeOutStuckRunningSteps(NOW);

        verify(orchestrator).completeStep(eq("exn_1"), eq("stp_a"), eq(Map.of("url", "http://x")), isNull(),
                contains("No result within 300s"), eq(true), eq(2), eq(true)); // uncertain: it may have run
    }

    @Test
    void oneFailingStepDoesNotStopTheRest() {
        when(stepRuns.findStuckRunning(anyLong(), anyInt()))
                .thenReturn(List.of(step("stp_a", StepStatus.RUNNING, 1), step("stp_b", StepStatus.RUNNING, 1)));
        doThrow(new RuntimeException("db blip")).when(orchestrator)
                .completeStep(any(), eq("stp_a"), any(), any(), any(), anyBoolean(), anyInt(), anyBoolean());

        sweeper.timeOutStuckRunningSteps(NOW);

        verify(orchestrator).completeStep(any(), eq("stp_b"), any(), any(), any(), anyBoolean(), anyInt(), anyBoolean());
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleReadyStepsAreRequeuedAndTheirClockReset() {
        StepRun a = step("stp_a", StepStatus.READY, 0), b = step("stp_b", StepStatus.READY, 1);
        when(stepRuns.lockStaleReady(anyLong(), anyInt())).thenReturn(List.of(a, b));

        sweeper.requeueStaleReadySteps(NOW);

        assertEquals(NOW, a.getReadyAt());
        assertEquals(NOW, b.getReadyAt());
        ArgumentCaptor<Iterable<StepTaskOutbox>> queued = ArgumentCaptor.forClass(Iterable.class);
        verify(outbox).saveAll(queued.capture());
        List<String> ids = new ArrayList<>();
        queued.getValue().forEach(r -> ids.add(r.getStepRunId()));
        assertEquals(List.of("stp_a", "stp_b"), ids);
    }

    @Test
    void noStaleReadyStepsWritesNothing() {
        sweeper.requeueStaleReadySteps(NOW);
        verify(outbox, never()).saveAll(any());
    }

    @Test
    void unstartedRunsAreStartedAndOneFailureDoesNotStopTheRest() {
        when(runs.findUnstartedRunIds(anyLong(), anyInt())).thenReturn(List.of("exn_a", "exn_b"));
        doThrow(new RuntimeException("boom")).when(orchestrator).onRunStarted("exn_a");

        sweeper.startUnstartedRuns(NOW);

        verify(orchestrator).onRunStarted("exn_a");
        verify(orchestrator).onRunStarted("exn_b");
    }
}
