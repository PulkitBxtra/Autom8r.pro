package com.bxtralabs.pod.backend.service;

import com.bxtralabs.pod.backend.catalog.CatalogService;
import com.bxtralabs.pod.backend.catalog.SecretMasker;
import com.bxtralabs.pod.backend.catalog.StepSettingsValidator;
import com.bxtralabs.pod.backend.common.ConflictException;
import com.bxtralabs.pod.backend.common.NotFoundException;
import com.bxtralabs.pod.backend.model.ConnectionView;
import com.bxtralabs.pod.backend.model.Workflow;
import com.bxtralabs.pod.backend.model.WorkflowVersion;
import com.bxtralabs.pod.backend.model.graph.GraphEdge;
import com.bxtralabs.pod.backend.model.graph.GraphNode;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import com.bxtralabs.pod.backend.repository.ConnectionViewRepository;
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
import tools.jackson.databind.json.JsonMapper;

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
    @Mock
    private ConnectionViewRepository connectionViewRepository;
    @Spy
    private GraphValidator graphValidator = new GraphValidator();
    @Spy
    private StepSettingsValidator stepSettingsValidator = realStepSettingsValidator();
    @Spy
    private SecretMasker secretMasker = new SecretMasker(catalog());
    @InjectMocks
    private WorkflowService workflowService;

    private static final GraphNode WEBHOOK = new GraphNode("t", "trigger", "Webhook", "trg_webhook_catch", "Catch Hook",
            null, Map.of(), null, null, null, null);

    private static final WorkflowGraph VALID = new WorkflowGraph(
            List.of(WEBHOOK,
                    new GraphNode("a", "action", "HTTP", "act_http_request", "Make a Request", "http_request",
                            Map.of("method", "GET", "url", "https://example.com"), null, null, null, null)),
            List.of(new GraphEdge("t", "a", null, null)));

    private static CatalogService catalog() {
        try {
            return new CatalogService(JsonMapper.builder().build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static StepSettingsValidator realStepSettingsValidator() {
        try {
            JsonMapper mapper = JsonMapper.builder().build();
            return new StepSettingsValidator(new CatalogService(mapper), mapper);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final WorkflowGraph INVALID = new WorkflowGraph(
            List.of(new GraphNode("a", "action", "slack", "aac_item", "Send", "send_message", Map.of(), null, null, null, null)),
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
        assertEquals(List.of("t", "a"), saved.getValue().getGraph().nodes().stream().map(GraphNode::id).toList());

        assertEquals("wfv_1", w.getCurrentVersionId());
        assertEquals("usr_1", w.getUserId());
        assertEquals("My flow", w.getName());
        assertEquals("trg_webhook_catch", w.getTriggerId());
    }

    @Test
    void updateAppendsNextVersion() {
        Workflow existing = new Workflow("wfl_1", "Old", "trg_item", "usr_1", null);
        existing.setCurrentVersionId("wfv_3");
        when(workflowRepository.findById("wfl_1")).thenReturn(Optional.of(existing));
        when(workflowVersionRepository.findFirstByWorkflowIdOrderByVersionDesc("wfl_1"))
                .thenReturn(Optional.of(new WorkflowVersion("wfl_1", 3, VALID)));

        Workflow w = workflowService.update("wfl_1", "usr_1", "Renamed", VALID, "wfv_3");

        assertEquals("wfv_4", w.getCurrentVersionId());
        assertEquals("Renamed", w.getName());
    }

    @Test
    void updateOfSomeoneElsesWorkflowLooksLikeNotFound() {
        when(workflowRepository.findById("wfl_1"))
                .thenReturn(Optional.of(new Workflow("wfl_1", "Theirs", "trg_item", "usr_other", null)));

        assertThrows(NotFoundException.class, () -> workflowService.update("wfl_1", "usr_1", "Mine now", VALID, null));
        verify(workflowVersionRepository, never()).saveAndFlush(any());
    }

    @Test
    void invalidGraphSavesNothing() {
        assertThrows(IllegalArgumentException.class, () -> workflowService.create("usr_1", "Bad", INVALID));
        verify(workflowRepository, never()).save(any());
        verify(workflowVersionRepository, never()).saveAndFlush(any());
    }

    // Trigger plus one GitHub step using the given connection.
    private static WorkflowGraph withConnection(String appId, String connectionId) {
        return new WorkflowGraph(
                List.of(WEBHOOK,
                        new GraphNode("a", "action", "GitHub", "act_github_create_issue", "Create Issue", "action",
                                Map.of("repository", "octo/repo", "title", "Hi"), null, appId, connectionId, null)),
                List.of(new GraphEdge("t", "a", null, null)));
    }

    @Test
    void aStepCanUseTheUsersOwnConnectionForItsApp() {
        when(connectionViewRepository.findAllById(List.of("con_1")))
                .thenReturn(List.of(new ConnectionView("con_1", "usr_1", "app_github")));

        workflowService.create("usr_1", "My flow", withConnection("app_github", "con_1"));

        verify(workflowVersionRepository).saveAndFlush(any());
    }

    @Test
    void graphsWithoutConnectionsDontLookAnyUp() {
        workflowService.create("usr_1", "My flow", VALID);
        verifyNoInteractions(connectionViewRepository);
    }

    @Test
    void someoneElsesOrAMissingConnectionIsRejectedTheSameWay() {
        when(connectionViewRepository.findAllById(List.of("con_theirs")))
                .thenReturn(List.of(new ConnectionView("con_theirs", "usr_other", "app_github")));
        when(connectionViewRepository.findAllById(List.of("con_gone"))).thenReturn(List.of());

        Exception theirs = assertThrows(IllegalArgumentException.class,
                () -> workflowService.create("usr_1", "Flow", withConnection("app_github", "con_theirs")));
        Exception gone = assertThrows(IllegalArgumentException.class,
                () -> workflowService.create("usr_1", "Flow", withConnection("app_github", "con_gone")));

        assertEquals(gone.getMessage(), theirs.getMessage());
        assertTrue(gone.getMessage().contains("\"Create Issue\" no longer exists"), gone.getMessage());
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void aConnectionForAnotherAppIsRejected() {
        when(connectionViewRepository.findAllById(List.of("con_1")))
                .thenReturn(List.of(new ConnectionView("con_1", "usr_1", "app_slack")));

        Exception e = assertThrows(IllegalArgumentException.class,
                () -> workflowService.create("usr_1", "Flow", withConnection("app_github", "con_1")));
        assertTrue(e.getMessage().contains("different app"), e.getMessage());

        // A step that doesn't say which app it is can't be matched to a connection either.
        assertThrows(IllegalArgumentException.class,
                () -> workflowService.create("usr_1", "Flow", withConnection(null, "con_1")));
        verify(workflowVersionRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateChecksConnectionsToo() {
        when(workflowRepository.findById("wfl_1"))
                .thenReturn(Optional.of(new Workflow("wfl_1", "Old", "trg_item", "usr_1", null)));
        when(connectionViewRepository.findAllById(List.of("con_gone"))).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> workflowService.update("wfl_1", "usr_1", "Flow", withConnection("app_github", "con_gone"), null));
        verify(workflowVersionRepository, never()).saveAndFlush(any());
    }

    @Test
    void savingOverANewerVersionIsRefused() {
        Workflow existing = new Workflow("wfl_1", "Flow", "trg_item", "usr_1", null);
        existing.setCurrentVersionId("wfv_4");
        when(workflowRepository.findById("wfl_1")).thenReturn(Optional.of(existing));

        Exception e = assertThrows(ConflictException.class,
                () -> workflowService.update("wfl_1", "usr_1", "Flow", VALID, "wfv_3"));
        assertTrue(e.getMessage().contains("changed since you opened it"), e.getMessage());
        verify(workflowVersionRepository, never()).saveAndFlush(any());
        assertEquals("wfv_4", existing.getCurrentVersionId());
    }

    @Test
    void savedStepsTakeTheirAppNameAndHandlerFromTheCatalog() {
        // A client claiming a GitHub step runs the HTTP handler (or naming it anything) doesn't stick.
        GraphNode sneaky = new GraphNode("a", "action", "Whatever", "act_github_create_issue", "Renamed", "http_request",
                Map.of("repository", "octo/repo", "title", "Hi", "made_up", "x"), null, null, null, null);
        workflowService.create("usr_1", "Flow", new WorkflowGraph(List.of(WEBHOOK, sneaky), List.of(new GraphEdge("t", "a", null, null))));

        ArgumentCaptor<WorkflowVersion> saved = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowVersionRepository).saveAndFlush(saved.capture());
        GraphNode stored = saved.getValue().getGraph().nodes().get(1);
        assertEquals("github.create_issue", stored.type());
        assertEquals("GitHub", stored.appName());
        assertEquals("app_github", stored.appId());
        assertEquals("Create Issue", stored.name());
        assertEquals(Map.of("repository", "octo/repo", "title", "Hi"), stored.parameters(), "unknown settings are dropped");
        assertTrue(stored.fields().stream().anyMatch(f -> f.key().equals("title") && f.required()), "rules are stamped for the run");
    }

    @Test
    void badSettingsAreRejectedBeforeAnythingIsSaved() {
        GraphNode missingTitle = new GraphNode("a", "action", "GitHub", "act_github_create_issue", "Create Issue", null,
                Map.of("repository", "octo/repo"), null, null, null, null);
        Exception e = assertThrows(IllegalArgumentException.class, () -> workflowService.create("usr_1", "Flow",
                new WorkflowGraph(List.of(WEBHOOK, missingTitle), List.of(new GraphEdge("t", "a", null, null)))));
        assertEquals("Step \"Create Issue\" needs Title", e.getMessage());
        verify(workflowRepository, never()).save(any());
    }
}
