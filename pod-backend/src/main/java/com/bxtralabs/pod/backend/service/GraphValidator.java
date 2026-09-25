package com.bxtralabs.pod.backend.service;

import com.bxtralabs.pod.backend.model.graph.GraphEdge;
import com.bxtralabs.pod.backend.model.graph.GraphNode;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bxtralabs.pod.backend.model.graph.GraphNode.KIND_ACTION;
import static com.bxtralabs.pod.backend.model.graph.GraphNode.KIND_TRIGGER;

// Rejects graphs the engine can't run. Throws IllegalArgumentException, which
// GlobalExceptionHandler turns into a 400 with the message, so messages are user-facing.
@Component
public class GraphValidator {

    static final int MAX_NODES = 200;
    // Matches the step id in "{{steps.<id>.output...}}".
    private static final Pattern STEP_REF = Pattern.compile("\\{\\{\\s*steps\\.([A-Za-z0-9_-]+)");

    public void validate(WorkflowGraph graph) {
        if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
            throw new IllegalArgumentException("Workflow has no steps");
        }
        if (graph.nodes().size() > MAX_NODES) {
            throw new IllegalArgumentException("Workflow has more than " + MAX_NODES + " steps");
        }
        List<GraphEdge> edges = graph.edges() == null ? List.of() : graph.edges();

        Map<String, GraphNode> byId = new LinkedHashMap<>();
        String triggerId = checkNodes(graph.nodes(), byId);

        Map<String, List<String>> children = new HashMap<>();
        Map<String, List<String>> parents = new HashMap<>();
        checkEdges(edges, byId, triggerId, children, parents);

        List<String> order = topologicalOrder(byId.keySet(), children, parents);
        checkReachable(triggerId, byId.keySet(), children);
        checkStepReferences(order, byId, parents);
    }

    // Unique ids, known kinds, exactly one trigger, every step configured. Returns the trigger's id.
    private String checkNodes(List<GraphNode> nodes, Map<String, GraphNode> byId) {
        String triggerId = null;
        for (GraphNode node : nodes) {
            if (node == null || isBlank(node.id())) {
                throw new IllegalArgumentException("Every step needs an id");
            }
            if (byId.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate step id: " + node.id());
            }
            if (KIND_TRIGGER.equals(node.kind())) {
                if (triggerId != null) {
                    throw new IllegalArgumentException("Workflow must have exactly one trigger");
                }
                triggerId = node.id();
            } else if (!KIND_ACTION.equals(node.kind())) {
                throw new IllegalArgumentException("Step " + node.id() + " has unknown kind: " + node.kind());
            }
            if (isBlank(node.appName()) || isBlank(node.itemId())) {
                throw new IllegalArgumentException("Step " + node.id() + " has no app selected");
            }
        }
        if (triggerId == null) {
            throw new IllegalArgumentException("Workflow must have a trigger");
        }
        return triggerId;
    }

    private void checkEdges(List<GraphEdge> edges, Map<String, GraphNode> byId, String triggerId,
                            Map<String, List<String>> children, Map<String, List<String>> parents) {
        Set<String> seen = new HashSet<>();
        for (GraphEdge edge : edges) {
            if (edge == null || !byId.containsKey(edge.from()) || !byId.containsKey(edge.to())) {
                throw new IllegalArgumentException("A connection points at a step that doesn't exist");
            }
            if (edge.from().equals(edge.to())) {
                throw new IllegalArgumentException("Step " + edge.from() + " can't depend on itself");
            }
            if (edge.to().equals(triggerId)) {
                throw new IllegalArgumentException("Nothing can run before the trigger");
            }
            if (!seen.add(edge.from() + "->" + edge.to())) {
                throw new IllegalArgumentException("Duplicate connection from " + edge.from() + " to " + edge.to());
            }
            children.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
            parents.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge.from());
        }
    }

    // Kahn's algorithm. If some steps never reach in-degree 0, they're on a cycle.
    private List<String> topologicalOrder(Set<String> ids, Map<String, List<String>> children,
                                          Map<String, List<String>> parents) {
        Map<String, Integer> inDegree = new HashMap<>();
        Deque<String> ready = new ArrayDeque<>();
        for (String id : ids) {
            int degree = parents.getOrDefault(id, List.of()).size();
            inDegree.put(id, degree);
            if (degree == 0) {
                ready.add(id);
            }
        }

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String child : children.getOrDefault(id, List.of())) {
                if (inDegree.merge(child, -1, Integer::sum) == 0) {
                    ready.add(child);
                }
            }
        }

        if (order.size() != ids.size()) {
            throw new IllegalArgumentException("Workflow has a loop; steps can't depend on each other in a cycle");
        }
        return order;
    }

    private void checkReachable(String triggerId, Set<String> ids, Map<String, List<String>> children) {
        Set<String> reached = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(triggerId));
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (reached.add(id)) {
                queue.addAll(children.getOrDefault(id, List.of()));
            }
        }
        for (String id : ids) {
            if (!reached.contains(id)) {
                throw new IllegalArgumentException("Step " + id + " isn't connected to the trigger");
            }
        }
    }

    // A step can only read {{steps.X...}} if X is guaranteed to have finished first, i.e. X is an ancestor.
    private void checkStepReferences(List<String> order, Map<String, GraphNode> byId,
                                     Map<String, List<String>> parents) {
        // Walking in topological order means every parent's ancestor set is already built.
        Map<String, Set<String>> ancestors = new HashMap<>();
        for (String id : order) {
            Set<String> set = new HashSet<>();
            for (String parent : parents.getOrDefault(id, List.of())) {
                set.add(parent);
                set.addAll(ancestors.get(parent));
            }
            ancestors.put(id, set);
        }

        for (String id : order) {
            Set<String> refs = new HashSet<>();
            collectStepRefs(byId.get(id).parameters(), refs);
            for (String ref : refs) {
                if (!ancestors.get(id).contains(ref)) {
                    throw new IllegalArgumentException(
                            "Step " + id + " uses output from " + ref + ", which doesn't run before it");
                }
            }
        }
    }

    // Parameters are arbitrary JSON, so templates can be nested anywhere inside maps and lists.
    private void collectStepRefs(Object value, Set<String> refs) {
        if (value instanceof String s) {
            Matcher m = STEP_REF.matcher(s);
            while (m.find()) {
                refs.add(m.group(1));
            }
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectStepRefs(v, refs));
        } else if (value instanceof Collection<?> list) {
            list.forEach(v -> collectStepRefs(v, refs));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
