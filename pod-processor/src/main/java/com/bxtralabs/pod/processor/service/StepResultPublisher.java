package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.config.StepTopics;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Reports a finished step to the orchestrator via step-results.
//
// The worker doesn't wait for Kafka's ack: waiting (~85ms round trip to the broker) kept each
// of the few worker threads idle per step and cut fast-step throughput ~9x. Instead, if the
// send fails (Kafka down, or the result is over Kafka's message size limit) the result is
// recorded directly through the Orchestrator. completeStep ignores steps that are no longer
// RUNNING, so if the Kafka message is also delivered, the second copy is a no-op.
//
// Trade-off: if the pod dies in the moment between a step finishing and Kafka acking its
// result, the step stays RUNNING until the stuck-step sweeper retries it. A normal shutdown
// flushes pending sends first (the producer is closed after the listeners stop).
@Service
public class StepResultPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Orchestrator orchestrator;
    private final JsonMapper jsonMapper;
    // Send callbacks run on the Kafka producer's single I/O thread; the fallback does a DB
    // transaction (and may wait on the run lock), so it must not run there.
    private final ExecutorService fallbackPool;

    @Autowired
    public StepResultPublisher(KafkaTemplate<String, String> kafkaTemplate, Orchestrator orchestrator, JsonMapper jsonMapper) {
        this(kafkaTemplate, orchestrator, jsonMapper, Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "step-result-fallback");
            t.setDaemon(true);
            return t;
        }));
    }

    StepResultPublisher(KafkaTemplate<String, String> kafkaTemplate, Orchestrator orchestrator, JsonMapper jsonMapper,
                        ExecutorService fallbackPool) {
        this.kafkaTemplate = kafkaTemplate;
        this.orchestrator = orchestrator;
        this.jsonMapper = jsonMapper;
        this.fallbackPool = fallbackPool;
    }

    public void publish(StepResultMessage result) {
        try {
            String payload = jsonMapper.writeValueAsString(result);
            kafkaTemplate.send(StepTopics.STEP_RESULTS, result.runId(), payload)
                    .whenCompleteAsync((sent, error) -> {
                        if (error != null) {
                            recordDirectly(result, error);
                        }
                    }, fallbackPool);
        } catch (Exception e) {
            // send() itself threw (e.g. serialization, or the producer is closed)
            recordDirectly(result, e);
        }
    }

    private void recordDirectly(StepResultMessage result, Throwable cause) {
        System.out.println("step-results publish failed for " + result.stepRunId()
                + ", recording result directly: " + cause);
        orchestrator.completeStep(result.runId(), result.stepRunId(), result.input(), result.output(),
                result.error(), result.retryable(), result.attempt(), result.isUncertain());
    }

    // Let in-flight fallbacks finish on shutdown rather than dropping them.
    @PreDestroy
    void shutdown() throws InterruptedException {
        fallbackPool.shutdown();
        fallbackPool.awaitTermination(30, TimeUnit.SECONDS);
    }
}
