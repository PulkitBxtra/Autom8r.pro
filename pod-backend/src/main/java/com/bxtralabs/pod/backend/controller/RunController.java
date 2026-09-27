package com.bxtralabs.pod.backend.controller;

import com.bxtralabs.pod.backend.service.AuthService;
import com.bxtralabs.pod.backend.service.RunService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class RunController {

    private final AuthService authService;
    private final RunService runService;

    public RunController(AuthService authService, RunService runService) {
        this.authService = authService;
        this.runService = runService;
    }

    // Most recent runs of a workflow, newest first (limit capped at 100).
    @GetMapping("/workflow/{id}/runs")
    public List<RunService.RunSummary> listRuns(@RequestHeader("Authorization") String authorizationHeader,
                                                @PathVariable String id,
                                                @RequestParam(defaultValue = "20") int limit) {
        String userId = authService.requireUserId(authorizationHeader);
        return runService.listForWorkflow(id, userId, limit);
    }

    // One run with every step's status, input/output/error, and the graph version it executed.
    @GetMapping("/run/{id}")
    public RunService.RunDetail getRun(@RequestHeader("Authorization") String authorizationHeader,
                                       @PathVariable String id) {
        String userId = authService.requireUserId(authorizationHeader);
        return runService.getForUser(id, userId);
    }
}
