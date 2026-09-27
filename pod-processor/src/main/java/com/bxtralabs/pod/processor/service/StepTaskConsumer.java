package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.config.StepTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

// Workers: one listener thread per partition (steps.worker-concurrency, matching
// steps.topic-partitions), each running the steps it receives one at a time. Their own
// consumer group, separate from the run consumer, so busy workers never delay new runs
// from starting. Extra threads beyond the partition count would sit idle.
@Service
public class StepTaskConsumer {

    private final StepExecutor stepExecutor;
    private final JsonMapper jsonMapper;

    public StepTaskConsumer(StepExecutor stepExecutor, JsonMapper jsonMapper) {
        this.stepExecutor = stepExecutor;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(
            topics = StepTopics.STEP_TASKS,
            groupId = "pod-processor-workers",
            concurrency = "${steps.worker-concurrency:2}"
    )
    public void consume(String payload, Acknowledgment ack) {
        StepTaskMessage task = jsonMapper.readValue(payload, StepTaskMessage.class);
        // Duplicate or late deliveries are no-ops: execute() only runs a step it can claim
        // READY -> RUNNING. If execute() throws we don't ack, and Kafka redelivers.
        stepExecutor.execute(task.runId(), task.stepRunId());
        ack.acknowledge();
    }
}
