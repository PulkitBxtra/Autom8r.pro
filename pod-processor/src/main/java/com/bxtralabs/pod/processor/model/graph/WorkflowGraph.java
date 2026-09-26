package com.bxtralabs.pod.processor.model.graph;

import java.util.List;

// Copy of pod-backend's WorkflowGraph; keep the two in sync.
public record WorkflowGraph(List<GraphNode> nodes, List<GraphEdge> edges) {
}
