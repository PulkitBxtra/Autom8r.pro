package com.bxtralabs.pod.backend.controller;

import com.bxtralabs.pod.backend.model.Action;
import com.bxtralabs.pod.backend.model.Workflow;
import com.bxtralabs.pod.backend.model.WorkflowVersion;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import com.bxtralabs.pod.backend.service.AuthService;
import com.bxtralabs.pod.backend.service.WorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    // Includes each workflow's current graph so list views can show step counts.
    // One version lookup per workflow; fine at today's list sizes, batch it if lists grow large.
    @GetMapping("/workflows")
    public List<WorkflowResponse> listWorkflows(@RequestHeader("Authorization") String authorizationHeader) {
        String userId = authService.requireUserId(authorizationHeader);
        return workflowService.listForUser(userId).stream().map(this::toResponse).toList();
    }

    @GetMapping("/workflow/{id}")
    public WorkflowResponse getWorkflow(@RequestHeader("Authorization") String authorizationHeader, @PathVariable String id) {
        String userId = authService.requireUserId(authorizationHeader);
        return toResponse(workflowService.getForUser(id, userId));
    }

    @PostMapping("/workflows")
    public WorkflowResponse createWorkflow(@RequestHeader("Authorization") String authorizationHeader,
                                           @Valid @RequestBody SaveWorkflowRequest request) {
        String userId = authService.requireUserId(authorizationHeader);
        return toResponse(workflowService.create(userId, request.name(), request.graph()));
    }

    @PutMapping("/workflow/{id}")
    public WorkflowResponse updateWorkflow(@RequestHeader("Authorization") String authorizationHeader,
                                           @PathVariable String id,
                                           @Valid @RequestBody SaveWorkflowRequest request) {
        String userId = authService.requireUserId(authorizationHeader);
        return toResponse(workflowService.update(id, userId, request.name(), request.graph()));
    }

    private WorkflowResponse toResponse(Workflow workflow) {
        WorkflowVersion version = workflowService.getCurrentVersion(workflow);
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getName(),
                workflow.getUserId(),
                workflow.getTriggerId(),
                workflow.getCurrentVersionId(),
                version == null ? null : version.getVersion(),
                version == null ? null : version.getGraph(),
                workflow.getActions()
        );
    }

    public record SaveWorkflowRequest(
            @NotBlank(message = "name is required") String name,
            @NotNull(message = "graph is required") WorkflowGraph graph
    ) {}

    // graph/version are null for workflows saved before versioning; those still have the legacy actions list.
    public record WorkflowResponse(
            String id,
            String name,
            String userId,
            String triggerId,
            String currentVersionId,
            Integer version,
            WorkflowGraph graph,
            List<Action> actions
    ) {}
}
