package com.bxtralabs.pod.backend.service;

import com.bxtralabs.pod.backend.common.ConflictException;
import com.bxtralabs.pod.backend.common.NotFoundException;
import com.bxtralabs.pod.backend.model.ConnectionView;
import com.bxtralabs.pod.backend.model.Workflow;
import com.bxtralabs.pod.backend.model.WorkflowVersion;
import com.bxtralabs.pod.backend.model.graph.GraphNode;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import com.bxtralabs.pod.backend.repository.ConnectionViewRepository;
import com.bxtralabs.pod.backend.repository.WorkflowRepository;
import com.bxtralabs.pod.backend.repository.WorkflowVersionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkflowService {

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowVersionRepository workflowVersionRepository;

    @Autowired
    private GraphValidator graphValidator;

    @Autowired
    private ConnectionViewRepository connectionViewRepository;

    public List<Workflow> listForUser(String userId) {
        return workflowRepository.findByUserId(userId);
    }

    public Workflow getForUser(String id, String userId) {
        Workflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Workflow not found: " + id));

        // Don't reveal that a workflow with this id exists if it belongs to someone else.
        if (!userId.equals(workflow.getUserId())) {
            throw new NotFoundException("Workflow not found: " + id);
        }
        return workflow;
    }

    // Null for workflows saved before versioning existed.
    public WorkflowVersion getCurrentVersion(Workflow workflow) {
        if (workflow.getCurrentVersionId() == null) {
            return null;
        }
        return workflowVersionRepository.findById(workflow.getCurrentVersionId()).orElse(null);
    }

    @Transactional
    public Workflow create(String userId, String name, WorkflowGraph graph) {
        // Validate before inserting anything, so a bad graph never leaves an empty workflow behind.
        graphValidator.validate(graph);
        checkConnections(userId, graph);

        Workflow workflow = new Workflow();
        workflow.setName(name);
        workflow.setUserId(userId);
        workflow = workflowRepository.save(workflow);

        saveNewVersion(workflow, graph);
        return workflowRepository.save(workflow);
    }

    // Every save is a new immutable version; runs already in flight keep the version they started on.
    // baseVersionId: the version the editor started from. If someone saved since, refuse rather than
    // silently replacing their change. Null skips the check.
    @Transactional
    public Workflow update(String id, String userId, String name, WorkflowGraph graph, String baseVersionId) {
        graphValidator.validate(graph);
        Workflow workflow = getForUser(id, userId);
        if (baseVersionId != null && !baseVersionId.equals(workflow.getCurrentVersionId())) {
            throw new ConflictException("This workflow was changed since you opened it. Reload to see the latest version.");
        }
        checkConnections(userId, graph);

        workflow.setName(name);

        saveNewVersion(workflow, graph);
        return workflowRepository.save(workflow);
    }

    // A step may only use one of the saving user's own connections, made for the step's app.
    // Someone else's connection gets the same message as a missing one, so ids can't be probed.
    private void checkConnections(String userId, WorkflowGraph graph) {
        List<String> ids = graph.nodes().stream()
                .map(GraphNode::connectionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<String, ConnectionView> found = connectionViewRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ConnectionView::getId, Function.identity()));

        for (GraphNode node : graph.nodes()) {
            if (node.connectionId() == null) {
                continue;
            }
            String step = node.name() == null || node.name().isBlank() ? node.id() : node.name();
            ConnectionView connection = found.get(node.connectionId());
            if (connection == null || !userId.equals(connection.getUserId())) {
                throw new IllegalArgumentException(
                        "The connection chosen for step \"" + step + "\" no longer exists; pick another one");
            }
            if (!connection.getAppId().equals(node.appId())) {
                throw new IllegalArgumentException(
                        "The connection chosen for step \"" + step + "\" is for a different app");
            }
        }
    }

    private void saveNewVersion(Workflow workflow, WorkflowGraph graph) {
        int nextVersion = workflowVersionRepository.findFirstByWorkflowIdOrderByVersionDesc(workflow.getId())
                .map(v -> v.getVersion() + 1)
                .orElse(1);

        // Flush now so a concurrent save racing for the same version number fails here on the
        // (workflow_id, version) unique constraint, which GlobalExceptionHandler turns into a 409.
        WorkflowVersion version = workflowVersionRepository.saveAndFlush(
                new WorkflowVersion(workflow.getId(), nextVersion, graph));

        workflow.setCurrentVersionId(version.getId());
        // Kept in sync so the workflow list can still show the trigger without loading the graph.
        workflow.setTriggerId(triggerItemId(graph));
    }

    private static String triggerItemId(WorkflowGraph graph) {
        return graph.nodes().stream()
                .filter(n -> GraphNode.KIND_TRIGGER.equals(n.kind()))
                .findFirst()
                .map(GraphNode::itemId)
                .orElse(null);
    }
}
