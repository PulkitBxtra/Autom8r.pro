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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrchestratorTest {

    @Mock
    private ExecutionRunRepository executionRunRepository;
    @Mock
    private StepRunRepository stepRunRepository;
    @Mock
    private WorkflowVersionRepository workflowVersionRepository;
    @InjectMocks
    private Orchestrator orchestrator;

    private static GraphNode node(String id, String kind) {
        return new GraphNode(id, kind, "app", "item", id, null, Map.of(), null);
    }

    private static GraphEdge edge(String from, String to) {
        return new GraphEdge(from, to, null);
    }

    // trigger fans out to a and b, which join at c.
    private static final WorkflowGraph DIAMOND = new WorkflowGraph(
            List.of(node("t", "trigger"), node("a", "action"), node("b", "action"), node("c", "action")),
            List.of(edge("t", "a"), edge("t", "b"), edge("a", "c"), edge("b", "c")));

    // WorkflowVersion is @Immutable with no setters, so tests build it reflectively.
    private static WorkflowVersion version(String id, WorkflowGraph graph) throws Exception {
        WorkflowVersion v = new WorkflowVersion();
        setField(v, "id", id);
        setField(v, "graph", graph);
        return v;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private ExecutionRun givenRun(String versionId, Object body) {
        ExecutionRun run = new ExecutionRun("exn_1", "wfl_1", "PENDING", 1L, null, new HashMap<>(Map.of("body", body)));
        run.setWorkflowVersionId(versionId);
        when(executionRunRepository.findById("exn_1")).thenReturn(Optional.of(run));
        return run;
    }

    private List<StepRun> savedSteps() {
        List<StepRun> all = new ArrayList<>();
        mockingDetails(stepRunRepository).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("save"))
                .forEach(i -> all.add((StepRun) i.getArgument(0)));
        // The trigger is saved twice (created, then completed); keep the latest state per node.
        Map<String, StepRun> byNode = new LinkedHashMap<>();
        all.forEach(s -> byNode.put(s.getNodeId(), s));
        return new ArrayList<>(byNode.values());
    }

    @Test
    void createsOneStepPerNodeWithInDegreeAsPendingDeps() throws Exception {
        givenRun("wfv_1", Map.of("id", 42));
        when(workflowVersionRepository.findById("wfv_1")).thenReturn(Optional.of(version("wfv_1", DIAMOND)));

        orchestrator.onRunStarted("exn_1");

        Map<String, Integer> pending = new HashMap<>();
        savedSteps().forEach(s -> pending.put(s.getNodeId(), s.getPendingDeps()));
        assertEquals(Map.of("t", 0, "a", 1, "b", 1, "c", 2), pending);
        savedSteps().forEach(s -> assertEquals("exn_1", s.getRunId()));
    }

    @Test
    void completesTriggerWithWebhookBodyAndLeavesActionsPending() throws Exception {
        givenRun("wfv_1", Map.of("id", 42));
        when(workflowVersionRepository.findById("wfv_1")).thenReturn(Optional.of(version("wfv_1", DIAMOND)));

        orchestrator.onRunStarted("exn_1");

        for (StepRun s : savedSteps()) {
            if (s.getNodeId().equals("t")) {
                assertEquals(StepStatus.SUCCEEDED, s.getStatus());
                assertEquals(Map.of("id", 42), s.getOutput().get("body"));
                assertNotNull(s.getEndedAt());
            } else {
                assertEquals(StepStatus.PENDING, s.getStatus());
                assertEquals(0, s.getActiveParents());
            }
        }
    }

    @Test
    void marksRunRunning() throws Exception {
        ExecutionRun run = givenRun("wfv_1", Map.of());
        when(workflowVersionRepository.findById("wfv_1")).thenReturn(Optional.of(version("wfv_1", DIAMOND)));

        orchestrator.onRunStarted("exn_1");

        assertEquals("RUNNING", run.getStatus());
        assertNull(run.getEndTimestamp());
    }

    @Test
    void duplicateDeliveryIsIgnored() {
        givenRun("wfv_1", Map.of());
        when(stepRunRepository.existsByRunId("exn_1")).thenReturn(true);

        orchestrator.onRunStarted("exn_1");

        verify(stepRunRepository, never()).save(any());
        verify(workflowVersionRepository, never()).findById(any());
    }

    @Test
    void missingVersionFailsRunWithReason() {
        ExecutionRun run = givenRun("wfv_gone", Map.of());
        when(workflowVersionRepository.findById("wfv_gone")).thenReturn(Optional.empty());

        orchestrator.onRunStarted("exn_1");

        assertEquals("FAILED", run.getStatus());
        assertNotNull(run.getEndTimestamp());
        assertTrue(String.valueOf(run.getMetadata().get("error")).contains("wfv_gone"));
        verify(stepRunRepository, never()).save(any());
    }

    @Test
    void runWithoutVersionIdFails() {
        ExecutionRun run = givenRun(null, Map.of());

        orchestrator.onRunStarted("exn_1");

        assertEquals("FAILED", run.getStatus());
        verify(stepRunRepository, never()).save(any());
    }

    @Test
    void graphWithoutTriggerFailsInsteadOfThrowing() throws Exception {
        ExecutionRun run = givenRun("wfv_1", Map.of());
        WorkflowGraph noTrigger = new WorkflowGraph(List.of(node("a", "action")), List.of());
        when(workflowVersionRepository.findById("wfv_1")).thenReturn(Optional.of(version("wfv_1", noTrigger)));

        assertDoesNotThrow(() -> orchestrator.onRunStarted("exn_1"));
        assertEquals("FAILED", run.getStatus());
    }

    @Test
    void unknownRunIsIgnored() {
        when(executionRunRepository.findById("exn_1")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> orchestrator.onRunStarted("exn_1"));
        verify(stepRunRepository, never()).save(any());
    }
}
