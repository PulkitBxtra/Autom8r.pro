package com.bxtralabs.pod.backend.service;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunServiceTest {

    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    private final WorkflowVersionRepository versionRepository = mock(WorkflowVersionRepository.class);
    private final ExecutionRunRepository runRepository = mock(ExecutionRunRepository.class);
    private final StepRunViewRepository stepRepository = mock(StepRunViewRepository.class);
    private final RunService service =
            new RunService(workflowService, workflowRepository, versionRepository, runRepository, stepRepository);

    private static final WorkflowGraph GRAPH = new WorkflowGraph(
            List.of(new GraphNode("t", "trigger", "Webhook", "x", null, null, Map.of(), null, null, null)), List.of());

    private static ExecutionRun run(String id, String workflowId, String versionId, String status, Map<String, Object> metadata) {
        ExecutionRun r = new ExecutionRun(id, status, 1000L, 2000L, metadata, null);
        r.setWorkflowId(workflowId);
        r.setWorkflowVersionId(versionId);
        return r;
    }

    private static WorkflowVersion version(String id, int number) {
        WorkflowVersion v = new WorkflowVersion("wfl_1", number, GRAPH);
        v.setId(id);
        return v;
    }

    private static StepRunView step(String id, String nodeId, String status) {
        StepRunView s = new StepRunView();
        ReflectionTestUtils.setField(s, "id", id);
        ReflectionTestUtils.setField(s, "nodeId", nodeId);
        ReflectionTestUtils.setField(s, "status", status);
        ReflectionTestUtils.setField(s, "attempt", 2);
        ReflectionTestUtils.setField(s, "output", Map.of("ok", true));
        return s;
    }

    // ---------- list ----------

    @Test
    void listChecksOwnershipFirst() {
        when(workflowService.getForUser("wfl_1", "usr_other")).thenThrow(new NotFoundException("Workflow not found"));

        assertThrows(NotFoundException.class, () -> service.listForWorkflow("wfl_1", "usr_other", 20));
        verifyNoInteractions(runRepository);
    }

    @Test
    void listReturnsRunsWithVersionNumbersAndErrors() {
        when(runRepository.findByWorkflowIdOrderByStartTimestampDesc(eq("wfl_1"), any())).thenReturn(List.of(
                run("exn_2", "wfl_1", "wfv_b", "FAILED", Map.of("error", "Step a failed: HTTP 500")),
                run("exn_1", "wfl_1", "wfv_a", "SUCCEEDED", Map.of("body", Map.of()))));
        when(versionRepository.findAllById(any())).thenReturn(List.of(version("wfv_a", 1), version("wfv_b", 2)));

        List<RunService.RunSummary> runs = service.listForWorkflow("wfl_1", "usr_1", 20);

        assertEquals(List.of("exn_2", "exn_1"), runs.stream().map(RunService.RunSummary::id).toList());
        assertEquals(2, runs.get(0).version());
        assertEquals("Step a failed: HTTP 500", runs.get(0).error());
        assertEquals(1, runs.get(1).version());
        assertNull(runs.get(1).error());
        verify(versionRepository, times(1)).findAllById(any()); // one query, not one per run
    }

    @Test
    void listLimitIsClampedTo1Through100() {
        when(runRepository.findByWorkflowIdOrderByStartTimestampDesc(any(), any())).thenReturn(List.of());
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);

        service.listForWorkflow("wfl_1", "usr_1", 5000);
        service.listForWorkflow("wfl_1", "usr_1", 0);

        verify(runRepository, times(2)).findByWorkflowIdOrderByStartTimestampDesc(eq("wfl_1"), page.capture());
        assertEquals(100, page.getAllValues().get(0).getPageSize());
        assertEquals(1, page.getAllValues().get(1).getPageSize());
    }

    // ---------- detail ----------

    @Test
    void detailIncludesStepsTriggerBodyAndTheVersionThatRan() {
        when(runRepository.findById("exn_1")).thenReturn(Optional.of(
                run("exn_1", "wfl_1", "wfv_a", "RUNNING", Map.of("body", Map.of("order", 42)))));
        when(workflowRepository.findById("wfl_1")).thenReturn(Optional.of(new Workflow("wfl_1", "W", "x", "usr_1", null)));
        when(versionRepository.findById("wfv_a")).thenReturn(Optional.of(version("wfv_a", 3)));
        when(stepRepository.findByRunIdOrderByCreatedAtAsc("exn_1"))
                .thenReturn(List.of(step("stp_1", "t", "SUCCEEDED"), step("stp_2", "a", "RETRY_WAIT")));

        RunService.RunDetail detail = service.getForUser("exn_1", "usr_1");

        assertEquals("RUNNING", detail.run().status());
        assertEquals(3, detail.run().version());
        assertEquals(Map.of("order", 42), detail.triggerBody());
        assertSame(GRAPH, detail.graph());
        assertEquals(List.of("t", "a"), detail.steps().stream().map(RunService.StepDetail::nodeId).toList());
        assertEquals("RETRY_WAIT", detail.steps().get(1).status());
        assertEquals(2, detail.steps().get(1).attempt());
    }

    @Test
    void someoneElsesRunLooksNotFound() {
        when(runRepository.findById("exn_1")).thenReturn(Optional.of(run("exn_1", "wfl_1", "wfv_a", "RUNNING", Map.of())));
        when(workflowRepository.findById("wfl_1")).thenReturn(Optional.of(new Workflow("wfl_1", "W", "x", "usr_owner", null)));

        assertThrows(NotFoundException.class, () -> service.getForUser("exn_1", "usr_other"));
        verifyNoInteractions(stepRepository);
    }

    @Test
    void unknownRunIsNotFound() {
        when(runRepository.findById("exn_x")).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.getForUser("exn_x", "usr_1"));
    }

    @Test
    void runWithoutAVersionStillLoads() {
        when(runRepository.findById("exn_1")).thenReturn(Optional.of(run("exn_1", "wfl_1", null, "FAILED",
                new HashMap<>(Map.of("error", "Workflow version null not found")))));
        when(workflowRepository.findById("wfl_1")).thenReturn(Optional.of(new Workflow("wfl_1", "W", "x", "usr_1", null)));
        when(stepRepository.findByRunIdOrderByCreatedAtAsc("exn_1")).thenReturn(List.of());

        RunService.RunDetail detail = service.getForUser("exn_1", "usr_1");

        assertNull(detail.graph());
        assertNull(detail.run().version());
        assertEquals("Workflow version null not found", detail.run().error());
    }
}
