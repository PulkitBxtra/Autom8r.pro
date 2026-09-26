package com.bxtralabs.pod.webhooks.controller;

import com.bxtralabs.pod.webhooks.service.WorkflowService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Workflow creation lives in pod-backend (POST /workflows), which validates and versions the graph.
@RestController
public class webhookController {

    private final WorkflowService workflowService;

    public webhookController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    // Hitting this triggers the automation for the given workflow and records an ExecutionRun.
    @PostMapping("/trigger/{workflowId}")
    public String triggerWorkflow(@PathVariable String workflowId, @RequestBody Object body) {
        return workflowService.triggerWorkflow(workflowId, body);
    }
}
