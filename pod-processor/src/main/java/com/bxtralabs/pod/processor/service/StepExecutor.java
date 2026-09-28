package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.ExecutionRun;
import com.bxtralabs.pod.processor.model.StepRun;
import com.bxtralabs.pod.processor.model.WorkflowVersion;
import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.model.graph.WorkflowGraph;
import com.bxtralabs.pod.processor.repository.ExecutionRunRepository;
import com.bxtralabs.pod.processor.repository.StepRunRepository;
import com.bxtralabs.pod.processor.repository.WorkflowOwnerRepository;
import com.bxtralabs.pod.processor.repository.WorkflowVersionRepository;
import com.bxtralabs.pod.processor.service.connections.ConnectionCredentialsClient;
import com.bxtralabs.pod.processor.service.handlers.StepCredentials;
import com.bxtralabs.pod.processor.service.handlers.ActionHandlerRegistry;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import com.bxtralabs.pod.processor.service.template.TemplateResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

// Runs one READY step: claim it, resolve its templates, call its handler, and report the
// result on step-results. Execution happens outside any transaction, so a slow
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
    private StepInputChecker inputChecker;

    @Autowired
    private WorkflowOwnerRepository workflowOwners;

    @Autowired
    private ConnectionCredentialsClient credentialsClient;

    @Autowired
    private ActionHandlerRegistry handlers;

    @Autowired
    private StepResultPublisher resultPublisher;

    public void execute(String runId, String stepRunId) {
        if (stepRunRepository.claim(stepRunId, System.currentTimeMillis()) == 0) {
            return; // already claimed, or cancelled because the run failed
        }

        Map<String, Object> input = null;
        Map<String, Object> output = null;
        String error = null;
        boolean retryable = false;
        // Set by the claim above. If loading the step fails, this stays 0, the result below is
        // ignored as not matching any attempt, and the sweeper recovers the step.
        int attempt = 0;
        try {
            StepRun step = stepRunRepository.findById(stepRunId).orElseThrow();
            attempt = step.getAttempt();
            ExecutionRun run = executionRunRepository.findById(runId).orElseThrow();
            WorkflowGraph graph = graphFor(run);
            GraphNode node = graph.nodes().stream()
                    .filter(n -> n.id().equals(step.getNodeId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Step " + step.getNodeId() + " is not in the workflow graph"));

            // Parents are finished, so their outputs are final; reading them without the run lock is safe.
            List<StepRun> steps = stepRunRepository.findByRunId(runId);
            input = templateResolver.resolveParameters(node.parameters(), TemplateResolver.context(graph, steps));
            // If the check fails, the run shows the input as resolved, which is what the user needs to see.
            input = inputChecker.check(node, input);
            // Fetched per attempt, used for this call only: never part of the recorded input.
            StepCredentials credentials = node.connectionId() == null ? null : credentialsFor(run, node);
            output = handlers.handlerFor(node).execute(node, input, credentials);
        } catch (PermanentStepException e) {
            error = e.getMessage();
        } catch (Exception e) {
            // Anything a handler didn't mark permanent (timeouts, connection errors, 5xx...) is
            // worth another attempt.
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            retryable = true;
        }

        // Goes to the orchestrator via step-results (or directly if Kafka won't take it). If
        // both paths fail (Kafka and the DB down) the step stays RUNNING; the stuck-step
        // sweeper (3.4) recovers it.
        resultPublisher.publish(new StepResultMessage(runId, stepRunId, input, output, error, retryable, attempt));
    }

    private StepCredentials credentialsFor(ExecutionRun run, GraphNode node) throws PermanentStepException {
        String owner = workflowOwners.findById(run.getWorkflowId())
                .orElseThrow(() -> new PermanentStepException("This run's workflow no longer exists"))
                .getUserId();
        return credentialsClient.fetch(node.connectionId(), owner, node.appId());
    }

    private WorkflowGraph graphFor(ExecutionRun run) {
        String versionId = run.getWorkflowVersionId();
        return workflowVersionRepository.findById(versionId)
                .map(WorkflowVersion::getGraph)
                .orElseThrow(() -> new IllegalStateException("Workflow version " + versionId + " not found"));
    }
}
