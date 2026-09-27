package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.config.StepTopics;
import com.bxtralabs.pod.processor.model.StepTaskOutbox;
import com.bxtralabs.pod.processor.repository.StepTaskOutboxRepository;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StepTaskPublisherTest {

    private final StepTaskOutboxRepository repo = mock(StepTaskOutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final StepTaskPublisher publisher = new StepTaskPublisher(
            repo, kafka, new TransactionTemplate(mock(PlatformTransactionManager.class)), jsonMapper);

    @BeforeEach
    void twoPartitions() {
        when(kafka.partitionsFor(StepTopics.STEP_TASKS)).thenReturn(List.of(
                new PartitionInfo(StepTopics.STEP_TASKS, 0, null, null, null),
                new PartitionInfo(StepTopics.STEP_TASKS, 1, null, null, null)));
    }

    private static StepTaskOutbox row(String id, String stepRunId) {
        StepTaskOutbox r = new StepTaskOutbox("exn_1", stepRunId);
        r.setId(id);
        return r;
    }

    private static List<StepTaskOutbox> rows(int n) {
        List<StepTaskOutbox> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(row("sto_" + i, "stp_" + i));
        }
        return out;
    }

    private void allSendsSucceed() {
        when(kafka.send(anyString(), anyInt(), anyString(), anyString()))
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
    void publishesEachRowKeyedByStepAndDeletesAckedRows() {
        when(repo.lockBatch(anyInt())).thenReturn(List.of(row("sto_1", "stp_a"), row("sto_2", "stp_b")));
        allSendsSucceed();

        publisher.publish();

        verify(kafka).send(eq(StepTopics.STEP_TASKS), anyInt(), eq("stp_a"), argThat(p ->
                jsonMapper.readValue(p, StepTaskMessage.class).equals(new StepTaskMessage("exn_1", "stp_a"))));
        verify(kafka).send(eq(StepTopics.STEP_TASKS), anyInt(), eq("stp_b"), anyString());
        assertEquals(List.of("sto_1", "sto_2"), deletedIds());
    }

    @Test
    void spreadsTasksEvenlyAcrossPartitionsRoundRobin() {
        when(repo.lockBatch(anyInt())).thenReturn(rows(6));
        allSendsSucceed();

        publisher.publish();

        ArgumentCaptor<Integer> partitions = ArgumentCaptor.forClass(Integer.class);
        verify(kafka, times(6)).send(anyString(), partitions.capture(), anyString(), anyString());
        assertEquals(List.of(0, 1, 0, 1, 0, 1), partitions.getAllValues());
    }

    @Test
    void roundRobinContinuesAcrossBatches() {
        when(repo.lockBatch(anyInt())).thenReturn(rows(3)).thenReturn(rows(1));
        allSendsSucceed();

        publisher.publish();
        publisher.publish();

        ArgumentCaptor<Integer> partitions = ArgumentCaptor.forClass(Integer.class);
        verify(kafka, times(4)).send(anyString(), partitions.capture(), anyString(), anyString());
        assertEquals(List.of(0, 1, 0, 1), partitions.getAllValues());
    }

    @Test
    void failedSendKeepsItsRowForTheNextTick() {
        when(repo.lockBatch(anyInt())).thenReturn(List.of(row("sto_1", "stp_a"), row("sto_2", "stp_b")));
        when(kafka.send(anyString(), anyInt(), eq("stp_a"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(kafka.send(anyString(), anyInt(), eq("stp_b"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.publish();

        assertEquals(List.of("sto_1"), deletedIds());
    }

    @Test
    void emptyOutboxSendsNothing() {
        when(repo.lockBatch(anyInt())).thenReturn(List.of());

        publisher.publish();

        verify(kafka, never()).send(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void fullBatchKeepsDrainingUntilOutboxIsEmpty() {
        when(repo.lockBatch(anyInt())).thenReturn(rows(StepTaskPublisher.BATCH_SIZE), List.of(row("sto_last", "stp_last")));
        allSendsSucceed();

        publisher.publish();

        verify(repo, times(2)).lockBatch(anyInt());
        assertEquals(StepTaskPublisher.BATCH_SIZE + 1, deletedIds().size());
    }

    @Test
    void stopsDrainingWhenSomethingFails() {
        when(repo.lockBatch(anyInt())).thenReturn(rows(StepTaskPublisher.BATCH_SIZE));
        when(kafka.send(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.publish();

        verify(repo, times(1)).lockBatch(anyInt());
        verify(repo).deleteAllByIdInBatch(argThat((Iterable<String> ids) -> !ids.iterator().hasNext()));
    }
}
