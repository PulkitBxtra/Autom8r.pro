package com.bxtralabs.pod.webhooks.controller;

import com.bxtralabs.pod.webhooks.model.Workflow;
import com.bxtralabs.pod.webhooks.security.JwtUtil;
import com.bxtralabs.pod.webhooks.service.WorkflowService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class webhookController {

    private final WorkflowService workflowService;
    private final JwtUtil jwtUtil;

    public webhookController(WorkflowService workflowService, JwtUtil jwtUtil) {
        this.workflowService = workflowService;
        this.jwtUtil = jwtUtil;
    }

    // Called by a logged-in user (via the frontend), so it's the one place
    // in this pod that needs auth -- it's what establishes ownership.
    @PostMapping("/create/workflow")
    public String createWorkflow(@RequestHeader("Authorization") String authorizationHeader, @RequestBody Workflow workflow) {
        String userId = jwtUtil.extractUserIdFromHeader(authorizationHeader);
        return workflowService.createWorkflow(workflow, userId);
    }

    // Hitting this triggers the automation for the given workflow and records an ExecutionRun.
    @PostMapping("/trigger/{workflowId}")
    public String triggerWorkflow(@PathVariable String workflowId, @RequestBody Object body) {
        return workflowService.triggerWorkflow(workflowId, body);
    }
}
