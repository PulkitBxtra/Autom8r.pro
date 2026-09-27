package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.config.StepTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

// Orchestrator side of step-results: applies each finished step to its run, which may
// release children (queued to step-tasks) or finish the run. Own consumer group, so a
// backlog of step work never delays results, and vice versa.
@Service
public class StepResultConsumer {

    private final Orchestrator orchestrator;
    private final JsonMapper jsonMapper;

    public StepResultConsumer(Orchestrator orchestrator, JsonMapper jsonMapper) {
        this.orchestrator = orchestrator;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(
            topics = StepTopics.STEP_RESULTS,
            groupId = "pod-processor-orchestrator",
            concurrency = "${steps.results-concurrency:2}"
    )
    public void consume(String payload, Acknowledgment ack) {
        StepResultMessage result = jsonMapper.readValue(payload, StepResultMessage.class);
        // completeStep commits before returning, so we only ack once the result is saved.
        // Redelivered duplicates are no-ops: the step is no longer RUNNING.
        orchestrator.completeStep(result.runId(), result.stepRunId(), result.input(), result.output(),
                result.error(), result.retryable());
        ack.acknowledge();
    }
}
