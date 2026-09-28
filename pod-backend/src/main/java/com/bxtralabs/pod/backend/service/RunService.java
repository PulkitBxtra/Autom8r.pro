package com.bxtralabs.pod.backend.service;

import com.bxtralabs.pod.backend.catalog.SecretMasker;
import com.bxtralabs.pod.backend.common.NotFoundException;
import com.bxtralabs.pod.backend.model.ExecutionRun;
import com.bxtralabs.pod.backend.model.StepRunView;
import com.bxtralabs.pod.backend.model.Workflow;
import com.bxtralabs.pod.backend.model.WorkflowVersion;
import com.bxtralabs.pod.backend.model.graph.GraphNode;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import com.bxtralabs.pod.backend.repository.ExecutionRunRepository;
import com.bxtralabs.pod.backend.repository.StepRunViewRepository;
import com.bxtralabs.pod.backend.repository.WorkflowRepository;
import com.bxtralabs.pod.backend.repository.WorkflowVersionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;

// Read side of execution: run history and per-step progress for the UI. Runs and steps are
// written by pod-webhooks and pod-processor; this only reads them, scoped to the caller's
// workflows.
@Service
public class RunService {

    static final int MAX_LIMIT = 100;

    private final WorkflowService workflowService;
    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository workflowVersionRepository;
    private final ExecutionRunRepository executionRunRepository;
    private final StepRunViewRepository stepRunViewRepository;
    private final SecretMasker secretMasker;

    public RunService(WorkflowService workflowService, WorkflowRepository workflowRepository,
                      WorkflowVersionRepository workflowVersionRepository,
                      ExecutionRunRepository executionRunRepository, StepRunViewRepository stepRunViewRepository,
                      SecretMasker secretMasker) {
        this.secretMasker = secretMasker;
        this.workflowService = workflowService;
        this.workflowRepository = workflowRepository;
        this.workflowVersionRepository = workflowVersionRepository;
        this.executionRunRepository = executionRunRepository;
        this.stepRunViewRepository = stepRunViewRepository;
    }

    public record RunSummary(String id, String workflowId, String workflowVersionId, Integer version, String status,
                             Long startTimestamp, Long endTimestamp, String error) {
    }

    public record StepDetail(String id, String nodeId, String status, Integer attempt, Map<String, Object> input,
                             Map<String, Object> output, String error, Long startedAt, Long endedAt,
                             Long nextAttemptAt) {
    }

    // graph is the version this run executed, which may be older than the workflow's current one.
    public record RunDetail(RunSummary run, Object triggerBody, WorkflowGraph graph, List<StepDetail> steps) {
    }

    public List<RunSummary> listForWorkflow(String workflowId, String userId, int limit) {
        workflowService.getForUser(workflowId, userId); // 404s if it isn't the caller's
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<ExecutionRun> runs = executionRunRepository
                .findByWorkflowIdOrderByStartTimestampDesc(workflowId, PageRequest.of(0, size));

        // One query for all the version numbers on the page.
        Set<String> versionIds = new HashSet<>();
        runs.forEach(r -> {
            if (r.getWorkflowVersionId() != null) {
                versionIds.add(r.getWorkflowVersionId());
            }
        });
        Map<String, Integer> versionNumbers = new HashMap<>();
        workflowVersionRepository.findAllById(versionIds).forEach(v -> versionNumbers.put(v.getId(), v.getVersion()));

        return runs.stream().map(r -> summary(r, versionNumbers.get(r.getWorkflowVersionId()))).toList();
    }

    public RunDetail getForUser(String runId, String userId) {
        ExecutionRun run = executionRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("Run not found: " + runId));
        // Same rule as workflows: someone else's run looks like it doesn't exist.
        Workflow workflow = run.getWorkflowId() == null ? null : workflowRepository.findById(run.getWorkflowId()).orElse(null);
        if (workflow == null || !userId.equals(workflow.getUserId())) {
            throw new NotFoundException("Run not found: " + runId);
        }

        WorkflowVersion version = run.getWorkflowVersionId() == null ? null
                : workflowVersionRepository.findById(run.getWorkflowVersionId()).orElse(null);
        // Inputs are shown with secret settings hidden, as the version that ran defines them.
        Map<String, GraphNode> nodes = new HashMap<>();
        if (version != null && version.getGraph() != null && version.getGraph().nodes() != null) {
            version.getGraph().nodes().forEach(n -> nodes.put(n.id(), n));
        }
        List<StepDetail> steps = stepRunViewRepository.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .map(s -> new StepDetail(s.getId(), s.getNodeId(), s.getStatus(), s.getAttempt(),
                        secretMasker.maskInput(nodes.get(s.getNodeId()), s.getInput()),
                        s.getOutput(), s.getError(), s.getStartedAt(), s.getEndedAt(), s.getNextAttemptAt()))
                .toList();
        Object triggerBody = run.getMetadata() == null ? null : run.getMetadata().get("body");

        return new RunDetail(summary(run, version == null ? null : version.getVersion()),
                triggerBody, version == null ? null : secretMasker.mask(version.getGraph()), steps);
    }

    private static RunSummary summary(ExecutionRun run, Integer version) {
        Object error = run.getMetadata() == null ? null : run.getMetadata().get("error");
        return new RunSummary(run.getId(), run.getWorkflowId(), run.getWorkflowVersionId(), version, run.getStatus(),
                run.getStartTimestamp(), run.getEndTimestamp(), error == null ? null : String.valueOf(error));
    }
}
