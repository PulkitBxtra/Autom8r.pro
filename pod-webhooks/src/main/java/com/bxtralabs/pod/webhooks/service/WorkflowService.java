package com.bxtralabs.pod.webhooks.service;

import com.bxtralabs.pod.webhooks.model.ExecutionRun;
import com.bxtralabs.pod.webhooks.model.ExecutionRunOutbox;
import com.bxtralabs.pod.webhooks.model.Workflow;
import com.bxtralabs.pod.webhooks.repository.ExecutionRunOutboxRepository;
import com.bxtralabs.pod.webhooks.repository.ExecutionRunRepository;
import com.bxtralabs.pod.webhooks.repository.WorkflowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class WorkflowService {

    @Autowired
    private ExecutionRunRepository executionRunRepository;

    @Autowired
    private WorkflowRepository workflowRepository;
    @Autowired
    private ExecutionRunOutboxRepository executionRunOutboxRepository;

    public String createWorkflow(Workflow workflow, String userId) {
        workflow.setUserId(userId);
        Workflow w = workflowRepository.save(workflow);
        return w.getId();
    }

    // Triggered by the webhook: verify the workflow exists, then record an ExecutionRun.
    // The run and its outbox row must commit together, or the outbox guarantee is worthless.
    @Transactional
    public String triggerWorkflow(String workflowId, Object body) {

        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        // Workflows saved before versioning have no graph for the engine to run.
        if (workflow.getCurrentVersionId() == null) {
            throw new IllegalArgumentException("Workflow " + workflowId + " has no saved graph yet; open it and save it again");
        }

        ExecutionRun executionRun = new ExecutionRun();
        executionRun.setWorkflowId(workflow.getId());
        executionRun.setWorkflowVersionId(workflow.getCurrentVersionId());
        executionRun.setStatus("PENDING");
        executionRun.setStartTimestamp(Instant.now().toEpochMilli());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("body", body);
        executionRun.setMetadata(metadata);

        String executionId= executionRunRepository.save(executionRun).getId();

        ExecutionRunOutbox executionRunOutbox = new ExecutionRunOutbox();
        executionRunOutbox.setExecutionId(executionId);
        executionRunOutbox.setExecutionRun(executionRun);

        executionRunOutboxRepository.save(executionRunOutbox);

        // endTimestamp stays null until the run actually completes.
        // TODO: push executionRun.getId() onto a queue (kafka/redis) for a worker to execute.



        return executionId;
    }
}
