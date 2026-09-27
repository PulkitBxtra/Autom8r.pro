package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.config.StepTopics;
import com.bxtralabs.pod.processor.model.StepTaskOutbox;
import com.bxtralabs.pod.processor.repository.StepTaskOutboxRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// Moves step_task_outbox rows onto the step-tasks topic. Same shape as pod-workflow's run
// publisher: send a batch in parallel, wait once for the acks, delete only acked rows.
// Each batch is its own transaction holding its rows' locks, so concurrent publishers skip
// each other's rows. A crash between ack and delete re-publishes a step; the worker's
// READY -> RUNNING claim makes that harmless.
@Service
public class StepTaskPublisher {

    static final int BATCH_SIZE = 100;
    private static final long ACK_TIMEOUT_SECONDS = 10;

    private final StepTaskOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final JsonMapper jsonMapper;
    private final AtomicInteger partitionCounter = new AtomicInteger();

    public StepTaskPublisher(StepTaskOutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                             TransactionTemplate transactionTemplate, JsonMapper jsonMapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.jsonMapper = jsonMapper;
    }

    // Short delay: every step of every run passes through here, so this directly adds to
    // per-step latency.
    @Scheduled(fixedDelay = 100)
    public void publish() {
        int[] sent = new int[2]; // [rows in batch, rows acked]
        do {
            transactionTemplate.executeWithoutResult(tx -> {
                List<StepTaskOutbox> batch = outboxRepository.lockBatch(BATCH_SIZE);
                sent[0] = batch.size();
                sent[1] = publishBatch(batch);
            });
        } while (sent[0] == BATCH_SIZE && sent[1] == sent[0]); // full and all acked => likely more waiting
    }

    // Round-robin across partitions so workers get an even share. Hashing the key spread steps
    // unevenly (3 of 4 parallel steps landed on one of 2 partitions), and Kafka's keyless
    // "sticky" partitioner can put a whole batch on one partition. Any two tasks in the topic
    // are independent -- a step is only queued after every step it depends on has finished --
    // so which partition (and so which order) they run in never affects their inputs.
    // Even by count, not by load: a partition busy with a slow step still gets its turn.
    private int nextPartition(int partitions) {
        return Math.floorMod(partitionCounter.getAndIncrement(), partitions);
    }

    // Returns how many rows were acked (and deleted).
    private int publishBatch(List<StepTaskOutbox> batch) {
        if (batch.isEmpty()) {
            return 0;
        }

        int partitions = Math.max(1, kafkaTemplate.partitionsFor(StepTopics.STEP_TASKS).size());
        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>();
        for (StepTaskOutbox row : batch) {
            String payload = jsonMapper.writeValueAsString(new StepTaskMessage(row.getRunId(), row.getStepRunId()));
            futures.add(kafkaTemplate.send(StepTopics.STEP_TASKS, nextPartition(partitions), row.getStepRunId(), payload));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Some sends failed or timed out; sort out which ones below.
        }

        List<String> ackedIds = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            CompletableFuture<SendResult<String, String>> f = futures.get(i);
            if (f.isDone() && !f.isCompletedExceptionally()) {
                ackedIds.add(batch.get(i).getId());
            } else {
                System.out.println("step-tasks publish failed for " + batch.get(i).getStepRunId() + ", will retry");
            }
        }
        outboxRepository.deleteAllByIdInBatch(ackedIds);
        return ackedIds.size();
    }
}
