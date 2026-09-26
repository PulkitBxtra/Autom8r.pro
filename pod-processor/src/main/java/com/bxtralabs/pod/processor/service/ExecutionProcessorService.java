package com.bxtralabs.pod.processor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

// Kafka entry point. The work happens in Orchestrator, whose @Transactional has
// committed by the time it returns, so we only ack once the run's steps are saved.
// If it throws, we don't ack and Kafka redelivers; onRunStarted is safe to repeat.
@Service
public class ExecutionProcessorService {

    @Autowired
    private Orchestrator orchestrator;

    @KafkaListener(
            topics = "workflow-events",
            groupId = "pod-processor"
    )
    public void consume(String executionId, Acknowledgment ack) {
        System.out.println("Processing execution: id='" + executionId + "'");
        orchestrator.onRunStarted(executionId);
        ack.acknowledge();
    }
}
