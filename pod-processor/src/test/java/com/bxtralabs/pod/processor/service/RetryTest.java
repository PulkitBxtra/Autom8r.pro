package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.StepRun;
import com.bxtralabs.pod.processor.model.StepStatus;
import com.bxtralabs.pod.processor.model.StepTaskOutbox;
import com.bxtralabs.pod.processor.repository.StepRunRepository;
import com.bxtralabs.pod.processor.repository.StepTaskOutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetryTest {

    // ---------- RetryPolicy ----------

    @Test
    void delaysDoubleFromTheBase() {
        RetryPolicy p = new RetryPolicy(10, 2000, 300_000, 0);
        assertEquals(2000, p.delayMs(1));
        assertEquals(4000, p.delayMs(2));
        assertEquals(8000, p.delayMs(3));
        assertEquals(16000, p.delayMs(4));
    }

    @Test
    void delaysAreCappedAtTheMax() {
        RetryPolicy p = new RetryPolicy(100, 2000, 300_000, 0);
        assertEquals(300_000, p.delayMs(10));
        assertEquals(300_000, p.delayMs(60), "huge attempt counts don't overflow");
    }

    @Test
    void noMoreAttemptsReturnsNull() {
        RetryPolicy p = new RetryPolicy(3, 2000, 300_000, 0);
        assertEquals(1_000 + 2000, p.nextAttemptAt(1, 1_000));
        assertEquals(1_000 + 4000, p.nextAttemptAt(2, 1_000));
        assertNull(p.nextAttemptAt(3, 1_000));
        assertNull(p.nextAttemptAt(4, 1_000));
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        RetryPolicy p = new RetryPolicy(3, 10_000, 300_000, 0.2);
        for (int i = 0; i < 1000; i++) {
            long d = p.delayMs(1);
            assertTrue(d >= 8000 && d <= 12000, "delay " + d);
        }
    }

    @Test
    void singleAttemptPolicyNeverRetries() {
        assertNull(new RetryPolicy(1, 2000, 300_000, 0).nextAttemptAt(1, 0));
    }

    // ---------- RetryScheduler ----------

    private final StepRunRepository stepRuns = mock(StepRunRepository.class);
    private final StepTaskOutboxRepository outbox = mock(StepTaskOutboxRepository.class);
    private final RetryScheduler scheduler =
            new RetryScheduler(stepRuns, outbox, new TransactionTemplate(mock(PlatformTransactionManager.class)));

    private static StepRun waiting(String id) {
        StepRun s = new StepRun("exn_1", "node_" + id, 0);
        s.setId(id);
        s.setStatus(StepStatus.RETRY_WAIT);
        s.setNextAttemptAt(1L);
        return s;
    }

    @Test
    @SuppressWarnings("unchecked")
    void dueRetriesBecomeReadyAndAreQueuedForDispatch() {
        StepRun a = waiting("stp_a"), b = waiting("stp_b");
        when(stepRuns.lockDueRetries(anyLong(), anyInt())).thenReturn(List.of(a, b));

        scheduler.releaseDueRetries();

        assertEquals(StepStatus.READY, a.getStatus());
        assertEquals(StepStatus.READY, b.getStatus());
        assertNull(a.getNextAttemptAt());
        ArgumentCaptor<Iterable<StepTaskOutbox>> queued = ArgumentCaptor.forClass(Iterable.class);
        verify(outbox).saveAll(queued.capture());
        List<String> ids = new ArrayList<>();
        queued.getValue().forEach(r -> ids.add(r.getStepRunId()));
        assertEquals(List.of("stp_a", "stp_b"), ids);
    }

    @Test
    void nothingDueDoesNothing() {
        when(stepRuns.lockDueRetries(anyLong(), anyInt())).thenReturn(List.of());

        scheduler.releaseDueRetries();

        verify(stepRuns, times(1)).lockDueRetries(anyLong(), anyInt());
    }

    @Test
    void fullBatchKeepsGoing() {
        List<StepRun> full = new ArrayList<>();
        for (int i = 0; i < RetryScheduler.BATCH_SIZE; i++) {
            full.add(waiting("stp_" + i));
        }
        when(stepRuns.lockDueRetries(anyLong(), anyInt())).thenReturn(full, List.of(waiting("stp_last")));

        scheduler.releaseDueRetries();

        verify(stepRuns, times(2)).lockDueRetries(anyLong(), anyInt());
    }
}
