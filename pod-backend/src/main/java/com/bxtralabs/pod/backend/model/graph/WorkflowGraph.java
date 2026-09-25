package com.bxtralabs.pod.backend.model.graph;

import java.util.List;

// The full DAG for one version of a workflow, stored as jsonb on WorkflowVersion.
// Shape mirrors the React Flow canvas so the frontend can round-trip it.
public record WorkflowGraph(List<GraphNode> nodes, List<GraphEdge> edges) {
}
