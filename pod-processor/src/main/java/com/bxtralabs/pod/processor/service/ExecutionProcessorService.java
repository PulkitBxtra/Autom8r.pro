package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.ExecutionRun;
import com.bxtralabs.pod.processor.repository.ExecutionRunRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class ExecutionProcessorService {

    @Autowired
    private ExecutionRunRepository executionRunRepository;

    @KafkaListener(
            topics = "workflow-events",
            groupId = "pod-processor"
    )
    public void consume(String executionId, Acknowledgment ack) {
        ExecutionRun executionRun = executionRunRepository.findById(executionId).orElse(null);
        if (executionRun == null) {
            System.out.println("ExecutionRun not found: " + executionId);
            ack.acknowledge();
            return;
        }

        try{
            Thread.sleep(2);
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("Processing execution: " + executionRun);
        // TODO: load the workflow's actions in sortingOrder and run them, then update status/endTimestamp.

        ack.acknowledge();
    }
}
