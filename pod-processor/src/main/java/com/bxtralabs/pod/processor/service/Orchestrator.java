package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.ExecutionRun;
import com.bxtralabs.pod.processor.model.StepRun;
import com.bxtralabs.pod.processor.model.StepStatus;
import com.bxtralabs.pod.processor.model.WorkflowVersion;
import com.bxtralabs.pod.processor.model.graph.GraphEdge;
import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.model.graph.WorkflowGraph;
import com.bxtralabs.pod.processor.repository.ExecutionRunRepository;
import com.bxtralabs.pod.processor.repository.StepRunRepository;
import com.bxtralabs.pod.processor.repository.WorkflowVersionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Drives a run through its DAG. Each method is one transaction, so the step rows
// and the run status always change together.
@Service
public class Orchestrator {

    public static final String RUN_RUNNING = "RUNNING";
    public static final String RUN_FAILED = "FAILED";

    @Autowired
    private ExecutionRunRepository executionRunRepository;

    @Autowired
    private StepRunRepository stepRunRepository;

    @Autowired
    private WorkflowVersionRepository workflowVersionRepository;

    // Creates one StepRun per node of the run's pinned graph and completes the trigger step.
    // Safe to call twice for the same run (Kafka is at-least-once): the second call is a no-op.
    @Transactional
    public void onRunStarted(String runId) {
        ExecutionRun run = executionRunRepository.findById(runId).orElse(null);
        if (run == null) {
            System.out.println("ExecutionRun not found: " + runId);
            return;
        }

        // Fast path for a redelivered message. If two deliveries ever race past this check,
        // the (run_id, node_id) unique constraint rejects the second transaction.
        if (stepRunRepository.existsByRunId(runId)) {
            System.out.println("Run " + runId + " already started, ignoring duplicate delivery");
            return;
        }

        WorkflowVersion version = run.getWorkflowVersionId() == null
                ? null
                : workflowVersionRepository.findById(run.getWorkflowVersionId()).orElse(null);
        if (version == null || version.getGraph() == null) {
            failRun(run, "Workflow version " + run.getWorkflowVersionId() + " not found");
            return;
        }

        WorkflowGraph graph = version.getGraph();
        // pod-backend's GraphValidator guarantees exactly one trigger; this only guards against bad rows.
        if (graph.nodes() == null || graph.nodes().stream().noneMatch(n -> GraphNode.KIND_TRIGGER.equals(n.kind()))) {
            failRun(run, "Workflow version " + version.getId() + " has no trigger");
            return;
        }

        Map<String, Integer> inDegree = new HashMap<>();
        for (GraphEdge edge : edgesOf(graph)) {
            inDegree.merge(edge.to(), 1, Integer::sum);
        }

        StepRun triggerStep = null;
        for (GraphNode node : graph.nodes()) {
            StepRun step = new StepRun(runId, node.id(), inDegree.getOrDefault(node.id(), 0));
            if (GraphNode.KIND_TRIGGER.equals(node.kind())) {
                triggerStep = step;
            }
            stepRunRepository.save(step);
        }

        run.setStatus(RUN_RUNNING);
        executionRunRepository.save(run);

        // The trigger has already "happened": its output is the webhook body, which is what
        // {{trigger.body.x}} templates in later steps read from.
        long now = System.currentTimeMillis();
        Map<String, Object> output = new HashMap<>();
        output.put("body", run.getMetadata() == null ? null : run.getMetadata().get("body"));
        triggerStep.setStatus(StepStatus.SUCCEEDED);
        triggerStep.setOutput(output);
        triggerStep.setStartedAt(now);
        triggerStep.setEndedAt(now);
        stepRunRepository.save(triggerStep);

        // TODO(2.3): onStepFinished(triggerStep) to release the trigger's children.
    }

    private void failRun(ExecutionRun run, String reason) {
        System.out.println("Run " + run.getId() + " failed: " + reason);
        Map<String, Object> metadata = run.getMetadata() == null ? new HashMap<>() : new HashMap<>(run.getMetadata());
        metadata.put("error", reason);
        run.setMetadata(metadata);
        run.setStatus(RUN_FAILED);
        run.setEndTimestamp(System.currentTimeMillis());
        executionRunRepository.save(run);
    }

    private static List<GraphEdge> edgesOf(WorkflowGraph graph) {
        return graph.edges() == null ? List.of() : graph.edges();
    }
}
