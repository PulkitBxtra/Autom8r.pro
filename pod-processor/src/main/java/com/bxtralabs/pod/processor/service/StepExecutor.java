package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.StepRun;
import com.bxtralabs.pod.processor.model.WorkflowVersion;
import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.model.graph.WorkflowGraph;
import com.bxtralabs.pod.processor.repository.ExecutionRunRepository;
import com.bxtralabs.pod.processor.repository.StepRunRepository;
import com.bxtralabs.pod.processor.repository.WorkflowVersionRepository;
import com.bxtralabs.pod.processor.service.handlers.ActionHandlerRegistry;
import com.bxtralabs.pod.processor.service.template.TemplateResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

// Runs one READY step: claim it, resolve its templates, call its handler, and hand the
// result back to the Orchestrator. Execution happens outside any transaction, so a slow
// step (e.g. an HTTP call) never holds the run lock.
@Service
public class StepExecutor {

    @Autowired
    private StepRunRepository stepRunRepository;

    @Autowired
    private ExecutionRunRepository executionRunRepository;

    @Autowired
    private WorkflowVersionRepository workflowVersionRepository;

    @Autowired
    private TemplateResolver templateResolver;

    @Autowired
    private ActionHandlerRegistry handlers;

    @Autowired
    private Orchestrator orchestrator;

    public void execute(String runId, String stepRunId) {
        if (stepRunRepository.claim(stepRunId, System.currentTimeMillis()) == 0) {
            return; // already claimed, or cancelled because the run failed
        }

        Map<String, Object> input = null;
        Map<String, Object> output = null;
        String error = null;
        try {
            StepRun step = stepRunRepository.findById(stepRunId).orElseThrow();
            WorkflowGraph graph = graphFor(runId);
            GraphNode node = graph.nodes().stream()
                    .filter(n -> n.id().equals(step.getNodeId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Step " + step.getNodeId() + " is not in the workflow graph"));

            // Parents are finished, so their outputs are final; reading them without the run lock is safe.
            List<StepRun> steps = stepRunRepository.findByRunId(runId);
            input = templateResolver.resolveParameters(node.parameters(), TemplateResolver.context(graph, steps));
            output = handlers.handlerFor(node).execute(node, input);
        } catch (Exception e) {
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        // If this throws (e.g. the DB is down) the step stays RUNNING; recovering stuck steps
        // is Phase 3 (a sweeper for RUNNING steps whose startedAt is too old).
        orchestrator.completeStep(runId, stepRunId, input, output, error);
    }

    private WorkflowGraph graphFor(String runId) {
        String versionId = executionRunRepository.findById(runId).orElseThrow().getWorkflowVersionId();
        return workflowVersionRepository.findById(versionId)
                .map(WorkflowVersion::getGraph)
                .orElseThrow(() -> new IllegalStateException("Workflow version " + versionId + " not found"));
    }
}
