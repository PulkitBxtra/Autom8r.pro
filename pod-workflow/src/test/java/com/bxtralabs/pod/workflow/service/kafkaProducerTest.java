package com.bxtralabs.pod.workflow.service;

import com.bxtralabs.pod.workflow.model.ExecutionRunOutbox;
import com.bxtralabs.pod.workflow.repository.RunOutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class kafkaProducerTest {

    private final RunOutboxRepository repo = mock(RunOutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final kafkaProducer producer = new kafkaProducer(kafka, new TransactionTemplate(txManager));

    {
        ReflectionTestUtils.setField(producer, "runOutboxRepository", repo);
    }

    private static ExecutionRunOutbox row(String id, String executionId) {
        return new ExecutionRunOutbox(id, executionId, null);
    }

    private static List<ExecutionRunOutbox> rows(int n) {
        List<ExecutionRunOutbox> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(row("ero_" + i, "exn_" + i));
        }
        return out;
    }

    private void allSendsSucceed() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @SuppressWarnings("unchecked")
    private List<String> deletedIds() {
        ArgumentCaptor<Iterable<String>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repo, atLeastOnce()).deleteAllByIdInBatch(captor.capture());
        List<String> all = new ArrayList<>();
        captor.getAllValues().forEach(it -> it.forEach(all::add));
        return all;
    }

    @Test
    void publishesLockedRowsKeyedByRunAndDeletesAckedOnes() {
        when(repo.lockBatch(anyInt())).thenReturn(List.of(row("ero_1", "exn_a"), row("ero_2", "exn_b")));
        allSendsSucceed();

        producer.publishEvents();

        verify(kafka).send("workflow-events", "exn_a", "exn_a");
        verify(kafka).send("workflow-events", "exn_b", "exn_b");
        assertEquals(List.of("ero_1", "ero_2"), deletedIds());
    }

    @Test
    void eachBatchRunsInItsOwnTransactionSoRowLocksAreHeldWhileSending() {
        when(repo.lockBatch(anyInt())).thenReturn(rows(kafkaProducer.BATCH_SIZE), rows(3));
        allSendsSucceed();

        producer.publishEvents();

        verify(txManager, times(2)).getTransaction(any());
        verify(txManager, times(2)).commit(any());
        verify(repo, times(2)).lockBatch(kafkaProducer.BATCH_SIZE);
    }

    @Test
    void failedSendKeepsItsRowAndStopsDraining() {
        when(repo.lockBatch(anyInt())).thenReturn(rows(kafkaProducer.BATCH_SIZE));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(kafka.send(anyString(), eq("exn_5"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        producer.publishEvents();

        List<String> deleted = deletedIds();
        assertEquals(kafkaProducer.BATCH_SIZE - 1, deleted.size());
        assertFalse(deleted.contains("ero_5"));
        verify(repo, times(1)).lockBatch(anyInt());
    }

    @Test
    void emptyOutboxSendsNothing() {
        when(repo.lockBatch(anyInt())).thenReturn(List.of());

        producer.publishEvents();

        verifyNoInteractions(kafka);
    }
}
