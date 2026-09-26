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
import com.bxtralabs.pod.processor.service.template.TemplateResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

// Drives a run through its DAG. Every public method is one transaction that starts by
// locking the run row, so all step bookkeeping for a run happens one change at a time:
// two parents finishing together can't both release a child, and the last two steps
// finishing together can't both miss that the run is done. Different runs don't block
// each other.
@Service
public class Orchestrator {

    public static final String RUN_RUNNING = "RUNNING";
    public static final String RUN_SUCCEEDED = "SUCCEEDED";
    public static final String RUN_FAILED = "FAILED";

    @Autowired
    private ExecutionRunRepository executionRunRepository;

    @Autowired
    private StepRunRepository stepRunRepository;

    @Autowired
    private WorkflowVersionRepository workflowVersionRepository;

    @Autowired
    private ConditionEvaluator conditionEvaluator;

    @Autowired
    private ApplicationEventPublisher events;

    // Creates one StepRun per node of the run's pinned graph, completes the trigger step,
    // and releases whatever the trigger unblocks.
    // Safe to call twice for the same run (Kafka is at-least-once): the second call is a no-op.
    @Transactional
    public void onRunStarted(String runId) {
        ExecutionRun run = executionRunRepository.findByIdForUpdate(runId).orElse(null);
        if (run == null) {
            System.out.println("ExecutionRun not found: " + runId);
            return;
        }

        // Redelivered message. The run lock makes this check reliable; the (run_id, node_id)
        // unique constraint is a second line of defence.
        if (stepRunRepository.existsByRunId(runId)) {
            System.out.println("Run " + runId + " already started, ignoring duplicate delivery");
            return;
        }

        WorkflowGraph graph = loadGraph(run);
        if (graph == null) {
            failRun(run, List.of(), "Workflow version " + run.getWorkflowVersionId() + " not found");
            return;
        }
        // pod-backend's GraphValidator guarantees exactly one trigger; this only guards against bad rows.
        if (graph.nodes() == null || graph.nodes().stream().noneMatch(n -> GraphNode.KIND_TRIGGER.equals(n.kind()))) {
            failRun(run, List.of(), "Workflow version " + run.getWorkflowVersionId() + " has no trigger");
            return;
        }

        Map<String, Integer> inDegree = new HashMap<>();
        for (GraphEdge edge : edgesOf(graph)) {
            inDegree.merge(edge.to(), 1, Integer::sum);
        }

        Map<String, StepRun> steps = new LinkedHashMap<>();
        StepRun triggerStep = null;
        for (GraphNode node : graph.nodes()) {
            StepRun step = stepRunRepository.save(new StepRun(runId, node.id(), inDegree.getOrDefault(node.id(), 0)));
            steps.put(node.id(), step);
            if (GraphNode.KIND_TRIGGER.equals(node.kind())) {
                triggerStep = step;
            }
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

        advance(run, graph, steps, triggerStep);
    }

    // Records the result of a step that was RUNNING and advances the run.
    // input is what the step ran with (templates resolved), kept for debugging and the UI.
    // error == null means success. Repeated or late calls are harmless: a step that isn't
    // RUNNING is ignored, and a run that already ended only records the step's result.
    @Transactional
    public void completeStep(String runId, String stepRunId, Map<String, Object> input,
                             Map<String, Object> output, String error) {
        ExecutionRun run = executionRunRepository.findByIdForUpdate(runId).orElse(null);
        if (run == null) {
            return;
        }

        List<StepRun> all = stepRunRepository.findByRunId(runId);
        Map<String, StepRun> steps = new LinkedHashMap<>();
        StepRun step = null;
        for (StepRun s : all) {
            steps.put(s.getNodeId(), s);
            if (s.getId().equals(stepRunId)) {
                step = s;
            }
        }
        if (step == null || step.getStatus() != StepStatus.RUNNING) {
            return;
        }

        step.setEndedAt(System.currentTimeMillis());
        step.setInput(input);
        if (error != null) {
            step.setStatus(StepStatus.FAILED);
            step.setError(error);
            stepRunRepository.save(step);
            if (!isEnded(run)) {
                failRun(run, all, "Step " + step.getNodeId() + " failed: " + error);
            }
            return;
        }

        step.setStatus(StepStatus.SUCCEEDED);
        step.setOutput(output);
        stepRunRepository.save(step);

        // A sibling failed while this step was running: keep its result, but don't start anything new.
        if (isEnded(run)) {
            return;
        }

        WorkflowGraph graph = loadGraph(run);
        if (graph == null) {
            failRun(run, all, "Workflow version " + run.getWorkflowVersionId() + " not found");
            return;
        }
        advance(run, graph, steps, step);
    }

    // Walks the edges out of a finished step. Each child loses one pending parent; if the edge
    // was taken it also gains an active parent. When a child has no pending parents left it is
    // READY if any parent reached it, otherwise SKIPPED -- and a skipped step is itself
    // "finished", so its children are processed the same way. That's how a branch that wasn't
    // taken skips everything below it without leaving joins waiting forever.
    private void advance(ExecutionRun run, WorkflowGraph graph, Map<String, StepRun> steps, StepRun finished) {
        Map<String, List<GraphEdge>> outgoing = new HashMap<>();
        for (GraphEdge edge : edgesOf(graph)) {
            outgoing.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
        }

        long now = System.currentTimeMillis();
        List<StepRun> ready = new ArrayList<>();
        Deque<StepRun> queue = new ArrayDeque<>(List.of(finished));
        // Conditions read outputs of steps that already succeeded, which don't change below.
        Map<String, Object> context = TemplateResolver.context(graph, steps.values());

        while (!queue.isEmpty()) {
            StepRun done = queue.poll();
            boolean doneRan = done.getStatus() == StepStatus.SUCCEEDED;

            for (GraphEdge edge : outgoing.getOrDefault(done.getNodeId(), List.of())) {
                StepRun child = steps.get(edge.to());
                if (child == null || child.getStatus() != StepStatus.PENDING) {
                    continue;
                }
                child.setPendingDeps(child.getPendingDeps() - 1);
                if (doneRan && conditionEvaluator.isTaken(edge, context)) {
                    child.setActiveParents(child.getActiveParents() + 1);
                }
                if (child.getPendingDeps() == 0) {
                    if (child.getActiveParents() > 0) {
                        child.setStatus(StepStatus.READY);
                        ready.add(child);
                    } else {
                        child.setStatus(StepStatus.SKIPPED);
                        child.setEndedAt(now);
                        queue.add(child);
                    }
                }
            }
        }

        stepRunRepository.saveAll(steps.values());

        if (steps.values().stream().allMatch(s -> s.getStatus().isTerminal())) {
            run.setStatus(RUN_SUCCEEDED);
            run.setEndTimestamp(now);
            executionRunRepository.save(run);
        }

        if (!ready.isEmpty()) {
            events.publishEvent(new StepsReadyEvent(run.getId(), ready.stream().map(StepRun::getId).toList()));
        }
    }

    // Fails the run and cancels every step that hasn't started. Steps already RUNNING are left
    // alone; when they finish, completeStep records their result and stops there.
    private void failRun(ExecutionRun run, List<StepRun> steps, String reason) {
        System.out.println("Run " + run.getId() + " failed: " + reason);
        long now = System.currentTimeMillis();
        for (StepRun s : steps) {
            if (s.getStatus() == StepStatus.PENDING || s.getStatus() == StepStatus.READY) {
                s.setStatus(StepStatus.CANCELLED);
                s.setEndedAt(now);
            }
        }
        stepRunRepository.saveAll(steps);

        Map<String, Object> metadata = run.getMetadata() == null ? new HashMap<>() : new HashMap<>(run.getMetadata());
        metadata.put("error", reason);
        run.setMetadata(metadata);
        run.setStatus(RUN_FAILED);
        run.setEndTimestamp(now);
        executionRunRepository.save(run);
    }

    private WorkflowGraph loadGraph(ExecutionRun run) {
        if (run.getWorkflowVersionId() == null) {
            return null;
        }
        return workflowVersionRepository.findById(run.getWorkflowVersionId())
                .map(WorkflowVersion::getGraph)
                .orElse(null);
    }

    private static boolean isEnded(ExecutionRun run) {
        return RUN_SUCCEEDED.equals(run.getStatus()) || RUN_FAILED.equals(run.getStatus());
    }

    private static List<GraphEdge> edgesOf(WorkflowGraph graph) {
        return graph.edges() == null ? List.of() : graph.edges();
    }
}
