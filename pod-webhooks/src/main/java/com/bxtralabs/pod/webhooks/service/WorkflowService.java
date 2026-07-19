package com.bxtralabs.pod.webhooks.service;

import com.bxtralabs.pod.webhooks.model.ExecutionRun;
import com.bxtralabs.pod.webhooks.model.Workflow;
import com.bxtralabs.pod.webhooks.repository.ExecutionRunRepository;
import com.bxtralabs.pod.webhooks.repository.WorkflowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class WorkflowService {

    @Autowired
    private ExecutionRunRepository executionRunRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    public String createWorkflow(Workflow workflow) {
        Workflow w = workflowRepository.save(workflow);
        return w.getId();
    }

    // Triggered by the webhook: verify the workflow exists, then record an ExecutionRun.
    public String triggerWorkflow(String workflowId, Object body) {

        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        ExecutionRun executionRun = new ExecutionRun();
        executionRun.setWorkflowId(workflow.getId());
        executionRun.setStatus("PENDING");
        executionRun.setStartTimestamp(Instant.now().toEpochMilli());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("body", body);
        executionRun.setMetadata(metadata);

        // endTimestamp stays null until the run actually completes.
        // TODO: push executionRun.getId() onto a queue (kafka/redis) for a worker to execute.

        return executionRunRepository.save(executionRun).getId();
    }
}
