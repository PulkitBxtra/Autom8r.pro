package com.bxtralabs.pod.webhooks.controller;

import com.bxtralabs.pod.webhooks.service.WorkflowService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // The same from a browser's address bar: the query parameters are the trigger's data
    // (?name=ada&tag=a&tag=b -> {"name": "ada", "tag": ["a", "b"]}).
    // Anything that fetches the link starts a run too, including chat apps previewing a pasted URL.
    @GetMapping("/trigger/{workflowId}")
    public Map<String, Object> triggerWorkflowFromBrowser(@PathVariable String workflowId,
                                                          @RequestParam MultiValueMap<String, String> query) {
        Map<String, Object> body = new LinkedHashMap<>();
        query.forEach((k, values) -> body.put(k, values.size() == 1 ? values.getFirst() : List.copyOf(values)));
        String runId = workflowService.triggerWorkflow(workflowId, body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("message", "Run started");
        out.put("data", body);
        return out;
    }
}
