package com.bxtralabs.pod.webhooks.controller;

import com.bxtralabs.pod.webhooks.service.WorkflowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

// For pod-connector: an app event (a GitHub issue opened...) starts a run of a workflow whose
// trigger listens for it. Other pods present the shared INTERNAL_API_TOKEN; with none configured
// every call is refused.
@RestController
public class InternalRunController {

    private final WorkflowService workflowService;
    private final byte[] internalToken;

    public InternalRunController(WorkflowService workflowService, @Value("${internal.api-token:}") String internalToken) {
        this.workflowService = workflowService;
        this.internalToken = internalToken == null ? new byte[0] : internalToken.trim().getBytes(StandardCharsets.UTF_8);
    }

    public record StartRequest(Object body) {
    }

    @PostMapping("/internal/workflows/{workflowId}/runs")
    public ResponseEntity<Map<String, String>> start(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                                     @PathVariable String workflowId,
                                                     @RequestBody StartRequest request) {
        if (internalToken.length < 16 || token == null
                || !MessageDigest.isEqual(internalToken, token.trim().getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not allowed"));
        }
        return ResponseEntity.ok(Map.of("runId", workflowService.triggerWorkflow(workflowId, request.body())));
    }
}
