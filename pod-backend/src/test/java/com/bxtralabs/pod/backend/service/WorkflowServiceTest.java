package com.bxtralabs.pod.backend.service;

import com.bxtralabs.pod.backend.common.NotFoundException;
import com.bxtralabs.pod.backend.model.Workflow;
import com.bxtralabs.pod.backend.model.WorkflowVersion;
import com.bxtralabs.pod.backend.model.graph.GraphEdge;
import com.bxtralabs.pod.backend.model.graph.GraphNode;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import com.bxtralabs.pod.backend.repository.WorkflowRepository;
import com.bxtralabs.pod.backend.repository.WorkflowVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private WorkflowVersionRepository workflowVersionRepository;
    @Spy
    private GraphValidator graphValidator = new GraphValidator();
    @InjectMocks
    private WorkflowService workflowService;

    private static final WorkflowGraph VALID = new WorkflowGraph(
            List.of(new GraphNode("t", "trigger", "webhook", "trg_item", "Webhook", null, Map.of(), null),
                    new GraphNode("a", "action", "slack", "aac_item", "Send", "send_message", Map.of(), null)),
            List.of(new GraphEdge("t", "a", null)));

    private static final WorkflowGraph INVALID = new WorkflowGraph(
            List.of(new GraphNode("a", "action", "slack", "aac_item", "Send", "send_message", Map.of(), null)),
            List.of());

    @BeforeEach
    void stubSaves() {
        // Mimic @PrePersist: assign ids on save, as the real repositories would.
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> {
            Workflow w = inv.getArgument(0);
            if (w.getId() == null) w.setId("wfl_new");
            return w;
        });
        when(workflowVersionRepository.saveAndFlush(any(WorkflowVersion.class))).thenAnswer(inv -> {
            WorkflowVersion v = inv.getArgument(0);
            v.setId("wfv_" + v.getVersion());
            return v;
        });
    }

    @Test
    void createSavesVersionOneAndPointsWorkflowAtIt() {
        when(workflowVersionRepository.findFirstByWorkflowIdOrderByVersionDesc("wfl_new")).thenReturn(Optional.empty());

        Workflow w = workflowService.create("usr_1", "My flow", VALID);

        ArgumentCaptor<WorkflowVersion> saved = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowVersionRepository).saveAndFlush(saved.capture());
        assertEquals(1, saved.getValue().getVersion());
        assertEquals("wfl_new", saved.getValue().getWorkflowId());
        assertSame(VALID, saved.getValue().getGraph());

        assertEquals("wfv_1", w.getCurrentVersionId());
        assertEquals("usr_1", w.getUserId());
        assertEquals("My flow", w.getName());
        assertEquals("trg_item", w.getTriggerId());
    }

    @Test
    void updateAppendsNextVersion() {
        Workflow existing = new Workflow("wfl_1", "Old", "trg_item", "usr_1", null);
        existing.setCurrentVersionId("wfv_3");
        when(workflowRepository.findById("wfl_1")).thenReturn(Optional.of(existing));
        when(workflowVersionRepository.findFirstByWorkflowIdOrderByVersionDesc("wfl_1"))
                .thenReturn(Optional.of(new WorkflowVersion("wfl_1", 3, VALID)));

        Workflow w = workflowService.update("wfl_1", "usr_1", "Renamed", VALID);

        assertEquals("wfv_4", w.getCurrentVersionId());
        assertEquals("Renamed", w.getName());
    }

    @Test
    void updateOfSomeoneElsesWorkflowLooksLikeNotFound() {
        when(workflowRepository.findById("wfl_1"))
                .thenReturn(Optional.of(new Workflow("wfl_1", "Theirs", "trg_item", "usr_other", null)));

        assertThrows(NotFoundException.class, () -> workflowService.update("wfl_1", "usr_1", "Mine now", VALID));
        verify(workflowVersionRepository, never()).saveAndFlush(any());
    }

    @Test
    void invalidGraphSavesNothing() {
        assertThrows(IllegalArgumentException.class, () -> workflowService.create("usr_1", "Bad", INVALID));
        verify(workflowRepository, never()).save(any());
        verify(workflowVersionRepository, never()).saveAndFlush(any());
    }
}
