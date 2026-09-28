package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.ExecutionRun;
import com.bxtralabs.pod.processor.model.StepRun;
import com.bxtralabs.pod.processor.model.StepStatus;
import com.bxtralabs.pod.processor.model.StepTaskOutbox;
import com.bxtralabs.pod.processor.model.WorkflowVersion;
import com.bxtralabs.pod.processor.model.graph.GraphEdge;
import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.model.graph.WorkflowGraph;
import com.bxtralabs.pod.processor.repository.ExecutionRunRepository;
import com.bxtralabs.pod.processor.repository.StepRunRepository;
import com.bxtralabs.pod.processor.repository.StepTaskOutboxRepository;
import com.bxtralabs.pod.processor.repository.WorkflowVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Drives whole runs through the Orchestrator against an in-memory step table. Dispatching is
// captured instead of executed, so each test decides exactly when and how each step finishes.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrchestratorTest {

    @Mock
    private ExecutionRunRepository executionRunRepository;
    @Mock
    private StepRunRepository stepRunRepository;
    @Mock
    private WorkflowVersionRepository workflowVersionRepository;
    @Mock
    private ConditionEvaluator conditionEvaluator;
    @Mock
    private StepTaskOutboxRepository stepTaskOutboxRepository;
    // 3 attempts, 1s base delay, no jitter: delays are exactly 1s then 2s.
    @Spy
    private RetryPolicy retryPolicy = new RetryPolicy(3, 1000, 60_000, 0);
    @InjectMocks
    private Orchestrator orchestrator;

    // In-memory step table, keyed by StepRun id.
    private final Map<String, StepRun> db = new LinkedHashMap<>();
    // Step ids queued in step_task_outbox for dispatch, in order.
    private final List<String> dispatched = new ArrayList<>();
    private ExecutionRun run;
    private int nextId;

    private static GraphNode node(String id, String kind) {
        return new GraphNode(id, kind, "app", "item", id, null, Map.of(), null, null, null, null);
    }

    private static GraphEdge edge(String from, String to) {
        return new GraphEdge(from, to, null, null);
    }

    private static GraphEdge conditional(String from, String to, String condition) {
        return new GraphEdge(from, to, condition, null);
    }

    private static WorkflowGraph graph(List<GraphNode> nodes, List<GraphEdge> edges) {
        return new WorkflowGraph(nodes, edges);
    }

    // trigger fans out to a and b, which join at c.
    private static final WorkflowGraph DIAMOND = graph(
            List.of(node("t", "trigger"), node("a", "action"), node("b", "action"), node("c", "action")),
            List.of(edge("t", "a"), edge("t", "b"), edge("a", "c"), edge("b", "c")));

    @BeforeEach
    void wireInMemoryRepositories() {
        when(stepRunRepository.save(any(StepRun.class))).thenAnswer(inv -> store(inv.getArgument(0)));
        when(stepRunRepository.saveAll(anyIterable())).thenAnswer(inv -> {
            Iterable<StepRun> steps = inv.getArgument(0);
            List<StepRun> saved = new ArrayList<>();
            steps.forEach(s -> saved.add(store(s)));
            return saved;
        });
        when(stepRunRepository.findByRunId(anyString())).thenAnswer(inv ->
                db.values().stream().filter(s -> s.getRunId().equals(inv.getArgument(0))).toList());
        when(stepRunRepository.existsByRunId(anyString())).thenAnswer(inv ->
                db.values().stream().anyMatch(s -> s.getRunId().equals(inv.getArgument(0))));
        when(stepTaskOutboxRepository.saveAll(anyIterable())).thenAnswer(inv -> {
            Iterable<StepTaskOutbox> rows = inv.getArgument(0);
            List<StepTaskOutbox> saved = new ArrayList<>();
            rows.forEach(r -> {
                assertEquals("exn_1", r.getRunId());
                dispatched.add(r.getStepRunId());
                saved.add(r);
            });
            return saved;
        });
        when(conditionEvaluator.isTaken(any(), any(), any())).thenReturn(true);
    }

    private StepRun store(StepRun s) {
        if (s.getId() == null) {
            s.setId("stp_" + (nextId++));
        }
        db.put(s.getId(), s);
        return s;
    }

    private void givenRun(WorkflowGraph graph) throws Exception {
        run = new ExecutionRun("exn_1", "wfl_1", "PENDING", 1L, null, new HashMap<>(Map.of("body", Map.of("id", 42))));
        run.setWorkflowVersionId("wfv_1");
        when(executionRunRepository.findByIdForUpdate("exn_1")).thenReturn(Optional.of(run));

        WorkflowVersion v = new WorkflowVersion();
        setField(v, "id", "wfv_1");
        setField(v, "graph", graph);
        when(workflowVersionRepository.findById("wfv_1")).thenReturn(Optional.of(v));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private StepRun step(String nodeId) {
        return db.values().stream().filter(s -> s.getNodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private StepStatus status(String nodeId) {
        return step(nodeId).getStatus();
    }

    private Set<String> dispatchedNodes() {
        Set<String> nodes = new LinkedHashSet<>();
        dispatched.forEach(id -> nodes.add(db.get(id).getNodeId()));
        return nodes;
    }

    // What StepExecutor does: claim (READY -> RUNNING), then report the result.
    private void runStep(String nodeId, Map<String, Object> output) {
        StepRun s = step(nodeId);
        assertEquals(StepStatus.READY, s.getStatus(), nodeId + " should be READY before it runs");
        s.setStatus(StepStatus.RUNNING);
        orchestrator.completeStep("exn_1", s.getId(), null, output, null, false, s.getAttempt());
    }

    private void failStep(String nodeId, String error) {
        StepRun s = step(nodeId);
        s.setStatus(StepStatus.RUNNING);
        orchestrator.completeStep("exn_1", s.getId(), null, null, error, false, s.getAttempt());
    }

    // ---------- starting a run ----------

    @Test
    void startCompletesTriggerAndReleasesItsChildren() throws Exception {
        givenRun(DIAMOND);

        orchestrator.onRunStarted("exn_1");

        assertEquals(StepStatus.SUCCEEDED, status("t"));
        assertEquals(Map.of("id", 42), step("t").getOutput().get("body"));
        assertEquals(StepStatus.READY, status("a"));
        assertEquals(StepStatus.READY, status("b"));
        assertEquals(StepStatus.PENDING, status("c"));
        assertEquals(2, step("c").getPendingDeps());
        assertEquals(Set.of("a", "b"), dispatchedNodes());
        assertEquals("RUNNING", run.getStatus());
    }

    @Test
    void duplicateStartIsIgnored() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");
        int stepsAfterFirst = db.size();
        dispatched.clear();

        orchestrator.onRunStarted("exn_1");

        assertEquals(stepsAfterFirst, db.size());
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void triggerOnlyWorkflowSucceedsImmediately() throws Exception {
        givenRun(graph(List.of(node("t", "trigger")), List.of()));

        orchestrator.onRunStarted("exn_1");

        assertEquals("SUCCEEDED", run.getStatus());
        assertNotNull(run.getEndTimestamp());
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void missingVersionFailsRun() throws Exception {
        givenRun(DIAMOND);
        when(workflowVersionRepository.findById("wfv_1")).thenReturn(Optional.empty());

        orchestrator.onRunStarted("exn_1");

        assertEquals("FAILED", run.getStatus());
        assertTrue(String.valueOf(run.getMetadata().get("error")).contains("wfv_1"));
        assertTrue(db.isEmpty());
    }

    @Test
    void graphWithoutTriggerFailsInsteadOfThrowing() throws Exception {
        givenRun(graph(List.of(node("a", "action")), List.of()));

        assertDoesNotThrow(() -> orchestrator.onRunStarted("exn_1"));
        assertEquals("FAILED", run.getStatus());
    }

    // ---------- advancing ----------

    @Test
    void joinWaitsForBothParentsThenRunsOnce() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");

        runStep("a", Map.of("a", 1));
        assertEquals(StepStatus.PENDING, status("c"), "c must wait for b too");
        assertEquals(1, step("c").getPendingDeps());

        runStep("b", Map.of("b", 1));
        assertEquals(StepStatus.READY, status("c"));
        assertEquals(1, dispatched.stream().filter(id -> db.get(id).getNodeId().equals("c")).count(),
                "c dispatched exactly once");

        runStep("c", Map.of("done", true));
        assertEquals("SUCCEEDED", run.getStatus());
        assertNotNull(run.getEndTimestamp());
        assertEquals(Map.of("done", true), step("c").getOutput());
    }

    @Test
    void runIsNotDoneWhileAnyStepIsStillOpen() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");
        runStep("a", Map.of());
        runStep("b", Map.of());

        assertEquals("RUNNING", run.getStatus());
    }

    @Test
    void edgeNotTakenSkipsChildButJoinStillRunsViaOtherParent() throws Exception {
        givenRun(graph(
                List.of(node("t", "trigger"), node("a", "action"), node("b", "action"), node("c", "action")),
                List.of(edge("t", "a"), conditional("t", "b", "no"), edge("a", "c"), edge("b", "c"))));
        when(conditionEvaluator.isTaken(argThat(e -> "no".equals(e.condition())), any(), any())).thenReturn(false);

        orchestrator.onRunStarted("exn_1");
        assertEquals(StepStatus.SKIPPED, status("b"));
        assertEquals(Set.of("a"), dispatchedNodes());

        runStep("a", Map.of());
        assertEquals(StepStatus.READY, status("c"), "c has one active parent (a), so it runs");

        runStep("c", Map.of());
        assertEquals("SUCCEEDED", run.getStatus());
    }

    @Test
    void skipPropagatesDownAWholeBranch() throws Exception {
        givenRun(graph(
                List.of(node("t", "trigger"), node("a", "action"), node("x", "action"), node("y", "action")),
                List.of(conditional("t", "a", "no"), edge("a", "x"), edge("x", "y"))));
        when(conditionEvaluator.isTaken(argThat(e -> "no".equals(e.condition())), any(), any())).thenReturn(false);

        orchestrator.onRunStarted("exn_1");

        assertEquals(StepStatus.SKIPPED, status("a"));
        assertEquals(StepStatus.SKIPPED, status("x"));
        assertEquals(StepStatus.SKIPPED, status("y"));
        assertTrue(dispatched.isEmpty());
        assertEquals("SUCCEEDED", run.getStatus(), "a run whose branches were all skipped still completes");
    }

    @Test
    void joinWhoseParentsAllSkippedIsSkipped() throws Exception {
        givenRun(graph(
                List.of(node("t", "trigger"), node("a", "action"), node("b", "action"), node("c", "action")),
                List.of(conditional("t", "a", "no"), conditional("t", "b", "no"), edge("a", "c"), edge("b", "c"))));
        when(conditionEvaluator.isTaken(argThat(e -> "no".equals(e.condition())), any(), any())).thenReturn(false);

        orchestrator.onRunStarted("exn_1");

        assertEquals(StepStatus.SKIPPED, status("c"));
        assertEquals("SUCCEEDED", run.getStatus());
    }

    @Test
    void conditionsAreOnlyCheckedForStepsThatRan() throws Exception {
        givenRun(graph(
                List.of(node("t", "trigger"), node("a", "action"), node("b", "action")),
                List.of(conditional("t", "a", "no"), edge("a", "b"))));
        when(conditionEvaluator.isTaken(argThat(e -> "no".equals(e.condition())), any(), any())).thenReturn(false);

        orchestrator.onRunStarted("exn_1");

        // a was skipped, so its edge to b must not be evaluated (it would read a's missing output).
        verify(conditionEvaluator, never()).isTaken(argThat(e -> "a".equals(e.from())), any(), any());
        assertEquals(StepStatus.SKIPPED, status("b"));
    }

    // ---------- failure ----------

    @Test
    void failedStepFailsRunAndCancelsWhatHasNotStarted() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");

        failStep("a", "boom");

        assertEquals(StepStatus.FAILED, status("a"));
        assertEquals("boom", step("a").getError());
        assertEquals(StepStatus.CANCELLED, status("b"), "b was READY but not started");
        assertEquals(StepStatus.CANCELLED, status("c"));
        assertEquals("FAILED", run.getStatus());
        assertNotNull(run.getEndTimestamp());
        assertTrue(String.valueOf(run.getMetadata().get("error")).contains("Step a failed: boom"));
    }

    @Test
    void siblingStillRunningWhenRunFailsIsRecordedButStartsNothing() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");
        step("b").setStatus(StepStatus.RUNNING); // b already claimed by a worker

        failStep("a", "boom");
        assertEquals(StepStatus.RUNNING, status("b"), "running steps aren't interrupted");

        dispatched.clear();
        orchestrator.completeStep("exn_1", step("b").getId(), null, Map.of("late", true), null, false, step("b").getAttempt());

        assertEquals(StepStatus.SUCCEEDED, status("b"));
        assertEquals(StepStatus.CANCELLED, status("c"));
        assertTrue(dispatched.isEmpty());
        assertEquals("FAILED", run.getStatus());
    }

    // ---------- idempotency ----------

    @Test
    void completingAStepTwiceIsHarmless() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");
        runStep("a", Map.of("first", true));

        orchestrator.completeStep("exn_1", step("a").getId(), null, Map.of("second", true), null, false, step("a").getAttempt());

        assertEquals(Map.of("first", true), step("a").getOutput());
        assertEquals(1, step("c").getPendingDeps(), "c must only be decremented once for a");
    }

    @Test
    void completingAStepThatWasNeverClaimedIsIgnored() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");

        orchestrator.completeStep("exn_1", step("a").getId(), null, Map.of(), null, false, step("a").getAttempt()); // a is READY, not RUNNING

        assertEquals(StepStatus.READY, status("a"));
        assertEquals(2, step("c").getPendingDeps());
    }

    @Test
    void resolvedInputIsRecordedOnSuccessAndFailure() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");

        step("a").setStatus(StepStatus.RUNNING);
        orchestrator.completeStep("exn_1", step("a").getId(), Map.of("to", "x@y.z"), Map.of("ok", true), null, false, step("a").getAttempt());
        assertEquals(Map.of("to", "x@y.z"), step("a").getInput());

        step("b").setStatus(StepStatus.RUNNING);
        orchestrator.completeStep("exn_1", step("b").getId(), Map.of("url", "http://bad"), null, "HTTP 500", false, step("b").getAttempt());
        assertEquals(Map.of("url", "http://bad"), step("b").getInput());
        assertEquals("HTTP 500", step("b").getError());
    }

    // ---------- retries ----------

    // A worker attempt: claim (READY -> RUNNING, attempt+1), then report a result.
    private void attempt(String nodeId, Map<String, Object> output, String error, boolean retryable) {
        StepRun s = step(nodeId);
        assertEquals(StepStatus.READY, s.getStatus(), nodeId + " should be READY before an attempt");
        s.setStatus(StepStatus.RUNNING);
        s.setAttempt(s.getAttempt() + 1);
        orchestrator.completeStep("exn_1", s.getId(), Map.of(), output, error, retryable, s.getAttempt());
    }

    // What RetryScheduler does once the wait is over.
    private void releaseRetry(String nodeId) {
        StepRun s = step(nodeId);
        assertEquals(StepStatus.RETRY_WAIT, s.getStatus());
        s.setStatus(StepStatus.READY);
        s.setNextAttemptAt(null);
    }

    @Test
    void temporaryFailureWaitsToRetryWithoutFailingTheRun() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");
        long before = System.currentTimeMillis();

        attempt("a", null, "HTTP 503", true);

        assertEquals(StepStatus.RETRY_WAIT, status("a"));
        assertEquals("HTTP 503", step("a").getError());
        long wait = step("a").getNextAttemptAt() - before;
        assertTrue(wait >= 1000 && wait < 1500, "first retry waits the base delay, was " + wait + "ms");
        assertEquals("RUNNING", run.getStatus());
        assertEquals(StepStatus.READY, status("b"), "siblings are untouched");
        assertEquals(2, step("c").getPendingDeps(), "children still waiting on a");
    }

    @Test
    void backoffDoublesBetweenAttempts() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");

        attempt("a", null, "HTTP 503", true);
        releaseRetry("a");
        long before = System.currentTimeMillis();
        attempt("a", null, "HTTP 503", true);

        long wait = step("a").getNextAttemptAt() - before;
        assertTrue(wait >= 2000 && wait < 2500, "second retry waits 2x base, was " + wait + "ms");
    }

    @Test
    void successOnRetryClearsTheErrorAndContinuesTheRun() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");
        runStep("b", Map.of());

        attempt("a", null, "HTTP 503", true);
        releaseRetry("a");
        attempt("a", Map.of("ok", true), null, false);

        assertEquals(StepStatus.SUCCEEDED, status("a"));
        assertNull(step("a").getError());
        assertNull(step("a").getNextAttemptAt());
        assertEquals(2, step("a").getAttempt());
        assertEquals(StepStatus.READY, status("c"));
    }

    @Test
    void outOfAttemptsFailsTheRunAndSaysHowManyAttempts() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");

        attempt("a", null, "HTTP 503", true);
        releaseRetry("a");
        attempt("a", null, "HTTP 503", true);
        releaseRetry("a");
        attempt("a", null, "HTTP 503", true); // 3rd of 3

        assertEquals(StepStatus.FAILED, status("a"));
        assertEquals("FAILED", run.getStatus());
        assertTrue(String.valueOf(run.getMetadata().get("error")).contains("after 3 attempts"),
                String.valueOf(run.getMetadata().get("error")));
        assertEquals(StepStatus.CANCELLED, status("c"));
    }

    @Test
    void permanentFailureFailsImmediatelyEvenWithAttemptsLeft() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");

        attempt("a", null, "HTTP 404", false);

        assertEquals(StepStatus.FAILED, status("a"));
        assertNull(step("a").getNextAttemptAt());
        assertEquals("FAILED", run.getStatus());
    }

    @Test
    void runFailureCancelsStepsWaitingToRetry() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");
        attempt("a", null, "HTTP 503", true);
        assertEquals(StepStatus.RETRY_WAIT, status("a"));

        attempt("b", null, "HTTP 404", false); // permanent -> run fails

        assertEquals(StepStatus.CANCELLED, status("a"));
        assertNull(step("a").getNextAttemptAt());
        assertEquals("FAILED", run.getStatus());
    }

    @Test
    void temporaryFailureAfterRunAlreadyFailedDoesNotWait() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");
        step("b").setStatus(StepStatus.RUNNING);
        step("b").setAttempt(1);
        attempt("a", null, "HTTP 404", false); // run fails while b is running

        orchestrator.completeStep("exn_1", step("b").getId(), Map.of(), null, "HTTP 503", true, step("b").getAttempt());

        assertEquals(StepStatus.FAILED, status("b"), "no point retrying for a run that already failed");
    }

    @Test
    void runIsNotDoneWhileAStepWaitsToRetry() throws Exception {
        givenRun(graph(List.of(node("t", "trigger"), node("a", "action")), List.of(edge("t", "a"))));
        orchestrator.onRunStarted("exn_1");

        attempt("a", null, "timeout", true);

        assertEquals("RUNNING", run.getStatus());
        assertNull(run.getEndTimestamp());
    }

    // ---------- attempts / sweeper interplay ----------

    @Test
    void lateResultFromAnEarlierAttemptIsIgnored() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");
        StepRun a = step("a");
        a.setStatus(StepStatus.RUNNING);
        a.setAttempt(2); // attempt 1 was timed out by the sweeper and retried

        orchestrator.completeStep("exn_1", a.getId(), Map.of(), Map.of("stale", true), null, false, 1);

        assertEquals(StepStatus.RUNNING, status("a"), "attempt 1's result must not complete attempt 2");
        assertNull(a.getOutput());
        assertEquals(2, step("c").getPendingDeps());
    }

    @Test
    void sweeperTimeoutRetriesTheStep() throws Exception {
        givenRun(DIAMOND);
        orchestrator.onRunStarted("exn_1");
        StepRun a = step("a");
        a.setStatus(StepStatus.RUNNING);
        a.setAttempt(1);

        // What StuckWorkSweeper reports for a step with no result.
        orchestrator.completeStep("exn_1", a.getId(), null, null, "No result within 300s", true, 1);

        assertEquals(StepStatus.RETRY_WAIT, status("a"));
        assertEquals("RUNNING", run.getStatus());
    }

    @Test
    void stepsBecomingReadyRecordWhen() throws Exception {
        givenRun(DIAMOND);
        long before = System.currentTimeMillis();

        orchestrator.onRunStarted("exn_1");

        assertNotNull(step("a").getReadyAt());
        assertTrue(step("a").getReadyAt() >= before);
        assertNull(step("c").getReadyAt(), "c isn't READY yet");
    }

    // C5: an uncertain failure is remembered on the step for every later attempt.
    @Test
    void anUncertainFailureStaysMarkedOnTheStep() throws Exception {
        givenRun(graph(List.of(node("t", "trigger"), node("a", "action")), List.of(edge("t", "a"))));
        orchestrator.onRunStarted("exn_1");
        StepRun a = step("a");

        a.setStatus(StepStatus.RUNNING);
        orchestrator.completeStep("exn_1", a.getId(), null, null, "No answer from GitHub", true, a.getAttempt(), true);
        assertEquals(StepStatus.RETRY_WAIT, a.getStatus());
        assertTrue(a.isUncertain());

        a.setStatus(StepStatus.RUNNING);
        orchestrator.completeStep("exn_1", a.getId(), null, null, "rate limited", true, a.getAttempt(), false);
        assertTrue(a.isUncertain(), "a later definite failure doesn't clear it");
    }
}
