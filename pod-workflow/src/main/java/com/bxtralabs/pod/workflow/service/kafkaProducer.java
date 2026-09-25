package com.bxtralabs.pod.workflow.service;

import com.bxtralabs.pod.workflow.model.ExecutionRunOutbox;
import com.bxtralabs.pod.workflow.repository.RunOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class kafkaProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    public kafkaProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Autowired
    private RunOutboxRepository runOutboxRepository;

    private static final String TOPIC = "workflow-events";
    // Must match the N in RunOutboxRepository.findFirstNByOrderByIdAsc.
    private static final int BATCH_SIZE = 100;
    private static final long ACK_TIMEOUT_SECONDS = 10;

    // Keyed by executionId so all events for one run land on the same partition, in order.
    public CompletableFuture<SendResult<String, String>> sendMessage(String topic, String key, String message) {
        return kafkaTemplate.send(topic, key, message);
    }


    // fixedDelay (not fixedRate) so a slow batch never overlaps the next tick.
    @Scheduled(fixedDelay = 200)
    public void publishEvents(){
        ArrayList<ExecutionRunOutbox> batch;
        do {
            batch = runOutboxRepository.findFirst100ByOrderByIdAsc();
            if (!publishBatch(batch)) {
                return; // something failed; back off until the next tick instead of hammering Kafka
            }
        } while (batch.size() == BATCH_SIZE); // full batch => likely a backlog, keep draining
    }

    // Sends the whole batch in parallel, waits once for all acks, then deletes only the acked rows.
    // Unacked rows stay for the next tick. A crash between ack and delete re-publishes a run
    // (at-least-once); the consumer must tolerate duplicates. Returns false if any send failed.
    private boolean publishBatch(List<ExecutionRunOutbox> batch) {
        if (batch.isEmpty()) {
            return true;
        }

        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>();
        for (ExecutionRunOutbox x : batch) {
            futures.add(sendMessage(TOPIC, x.getExecutionId(), x.getExecutionId()));
        }

        // One shared deadline for the batch, rather than up to ACK_TIMEOUT per message.
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Some sends failed or timed out; sort out which ones below.
        }

        List<String> ackedIds = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            CompletableFuture<SendResult<String, String>> f = futures.get(i);
            if (f.isDone() && !f.isCompletedExceptionally()) {
                ackedIds.add(batch.get(i).getId());
            } else {
                System.out.println("Outbox publish failed for " + batch.get(i).getExecutionId() + ", will retry");
            }
        }

        runOutboxRepository.deleteAllByIdInBatch(ackedIds);
        return ackedIds.size() == batch.size();
    }


}
