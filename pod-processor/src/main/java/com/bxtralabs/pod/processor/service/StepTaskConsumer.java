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

    // One step per poll: a batch would make queued steps wait behind each other on one thread
    // while other workers sit idle, and a long batch would outlast max.poll.interval.ms and get
    // this worker thrown out of the group. 10 minutes covers the longest step (an HTTP call has
    // at most 120s in all, a script 30s plus startup) with room to spare.
    @KafkaListener(
            topics = StepTopics.STEP_TASKS,
            groupId = "pod-processor-workers",
            concurrency = "${steps.worker-concurrency:2}",
            properties = {"max.poll.records=1", "max.poll.interval.ms=600000"}
    )
    public void consume(String payload, Acknowledgment ack) {
        StepTaskMessage task = jsonMapper.readValue(payload, StepTaskMessage.class);
        // Duplicate or late deliveries are no-ops: execute() only runs a step it can claim
        // READY -> RUNNING. If execute() throws we don't ack, and Kafka redelivers.
        stepExecutor.execute(task.runId(), task.stepRunId());
        ack.acknowledge();
    }
}
