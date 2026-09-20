package com.bxtralabs.pod.backend.controller;

import com.bxtralabs.pod.backend.model.Workflow;
import com.bxtralabs.pod.backend.service.AuthService;
import com.bxtralabs.pod.backend.service.WorkflowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class WorkflowController {

    private final AuthService authService;
    private final WorkflowService workflowService;

    public WorkflowController(AuthService authService, WorkflowService workflowService) {
        this.authService = authService;
        this.workflowService = workflowService;
    }

    @GetMapping("/workflows")
    public List<Workflow> listWorkflows(@RequestHeader("Authorization") String authorizationHeader) {
        String userId = authService.requireUserId(authorizationHeader);
        return workflowService.listForUser(userId);
    }

    @GetMapping("/workflow/{id}")
    public Workflow getWorkflow(@RequestHeader("Authorization") String authorizationHeader, @PathVariable String id) {
        String userId = authService.requireUserId(authorizationHeader);
        return workflowService.getForUser(id, userId);
    }
}
