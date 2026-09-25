package com.bxtralabs.pod.backend.service;

import com.bxtralabs.pod.backend.model.graph.GraphEdge;
import com.bxtralabs.pod.backend.model.graph.GraphNode;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphValidatorTest {

    private final GraphValidator validator = new GraphValidator();

    private static GraphNode trigger(String id) {
        return new GraphNode(id, "trigger", "webhook", "trg_item", "Webhook", null, Map.of(), null);
    }

    private static GraphNode action(String id) {
        return action(id, Map.of());
    }

    private static GraphNode action(String id, Map<String, Object> parameters) {
        return new GraphNode(id, "action", "slack", "aac_item", "Send message", "send_message", parameters, null);
    }

    private static GraphEdge edge(String from, String to) {
        return new GraphEdge(from, to, null);
    }

    private void assertRejected(WorkflowGraph graph, String messageFragment) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> validator.validate(graph));
        assertTrue(ex.getMessage().contains(messageFragment),
                "expected message containing '" + messageFragment + "' but was '" + ex.getMessage() + "'");
    }

    @Test
    void acceptsTriggerOnly() {
        assertDoesNotThrow(() -> validator.validate(new WorkflowGraph(List.of(trigger("t")), List.of())));
    }

    @Test
    void acceptsLinearChain() {
        WorkflowGraph g = new WorkflowGraph(
                List.of(trigger("t"), action("a"), action("b")),
                List.of(edge("t", "a"), edge("a", "b")));
        assertDoesNotThrow(() -> validator.validate(g));
    }

    @Test
    void acceptsDiamondWithFanOutAndJoin() {
        WorkflowGraph g = new WorkflowGraph(
                List.of(trigger("t"), action("a"), action("b"), action("join")),
                List.of(edge("t", "a"), edge("t", "b"), edge("a", "join"), edge("b", "join")));
        assertDoesNotThrow(() -> validator.validate(g));
    }

    @Test
    void acceptsReferenceToIndirectAncestor() {
        WorkflowGraph g = new WorkflowGraph(
                List.of(trigger("t"), action("a"), action("b"),
                        action("c", Map.of("text", "id is {{ steps.a.output.id }}"))),
                List.of(edge("t", "a"), edge("a", "b"), edge("b", "c")));
        assertDoesNotThrow(() -> validator.validate(g));
    }

    @Test
    void rejectsEmptyGraph() {
        assertRejected(new WorkflowGraph(List.of(), List.of()), "no steps");
    }

    @Test
    void rejectsMissingTrigger() {
        assertRejected(new WorkflowGraph(List.of(action("a")), List.of()), "must have a trigger");
    }

    @Test
    void rejectsTwoTriggers() {
        assertRejected(new WorkflowGraph(List.of(trigger("t1"), trigger("t2")), List.of()), "exactly one trigger");
    }

    @Test
    void rejectsDuplicateIds() {
        assertRejected(new WorkflowGraph(List.of(trigger("t"), action("a"), action("a")), List.of(edge("t", "a"))),
                "Duplicate step id");
    }

    @Test
    void rejectsUnknownKind() {
        GraphNode weird = new GraphNode("x", "loop", "slack", "aac_item", "X", null, Map.of(), null);
        assertRejected(new WorkflowGraph(List.of(trigger("t"), weird), List.of(edge("t", "x"))), "unknown kind");
    }

    @Test
    void rejectsUnconfiguredStep() {
        GraphNode empty = new GraphNode("a", "action", null, null, null, null, Map.of(), null);
        assertRejected(new WorkflowGraph(List.of(trigger("t"), empty), List.of(edge("t", "a"))), "no app selected");
    }

    @Test
    void rejectsEdgeToMissingStep() {
        assertRejected(new WorkflowGraph(List.of(trigger("t")), List.of(edge("t", "ghost"))), "doesn't exist");
    }

    @Test
    void rejectsSelfLoop() {
        assertRejected(new WorkflowGraph(List.of(trigger("t"), action("a")), List.of(edge("t", "a"), edge("a", "a"))),
                "can't depend on itself");
    }

    @Test
    void rejectsEdgeIntoTrigger() {
        assertRejected(new WorkflowGraph(List.of(trigger("t"), action("a")), List.of(edge("t", "a"), edge("a", "t"))),
                "before the trigger");
    }

    @Test
    void rejectsDuplicateEdge() {
        assertRejected(new WorkflowGraph(List.of(trigger("t"), action("a")), List.of(edge("t", "a"), edge("t", "a"))),
                "Duplicate connection");
    }

    @Test
    void rejectsCycle() {
        WorkflowGraph g = new WorkflowGraph(
                List.of(trigger("t"), action("a"), action("b"), action("c")),
                List.of(edge("t", "a"), edge("a", "b"), edge("b", "c"), edge("c", "a")));
        assertRejected(g, "loop");
    }

    @Test
    void rejectsDisconnectedStep() {
        WorkflowGraph g = new WorkflowGraph(List.of(trigger("t"), action("a"), action("orphan")), List.of(edge("t", "a")));
        assertRejected(g, "orphan isn't connected");
    }

    @Test
    void rejectsReferenceToSiblingBranch() {
        // a and b run in parallel, so b can't rely on a's output existing.
        WorkflowGraph g = new WorkflowGraph(
                List.of(trigger("t"), action("a"), action("b", Map.of("text", "{{steps.a.output.id}}"))),
                List.of(edge("t", "a"), edge("t", "b")));
        assertRejected(g, "uses output from a");
    }

    @Test
    void rejectsReferenceToDescendant() {
        WorkflowGraph g = new WorkflowGraph(
                List.of(trigger("t"), action("a", Map.of("text", "{{steps.b.output.id}}")), action("b")),
                List.of(edge("t", "a"), edge("a", "b")));
        assertRejected(g, "uses output from b");
    }

    @Test
    void findsReferencesNestedInsideListsAndMaps() {
        Map<String, Object> params = Map.of("blocks", List.of(Map.of("text", "{{steps.nope.output}}")));
        WorkflowGraph g = new WorkflowGraph(List.of(trigger("t"), action("a", params)), List.of(edge("t", "a")));
        assertRejected(g, "uses output from nope");
    }

    @Test
    void rejectsTooManySteps() {
        List<GraphNode> nodes = new ArrayList<>(List.of(trigger("t")));
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i < GraphValidator.MAX_NODES; i++) {
            nodes.add(action("a" + i));
            edges.add(edge("t", "a" + i));
        }
        assertRejected(new WorkflowGraph(nodes, edges), "more than");
    }
}
