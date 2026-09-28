package com.bxtralabs.pod.processor.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// Copy of pod-backend's WorkflowGraph; keep the two in sync.
// Graphs are written by pod-backend. Properties this pod doesn't know yet (pod-backend deployed
// first with a new one) are ignored instead of failing every run of the workflow; the same
// annotation is on each graph record here.
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowGraph(List<GraphNode> nodes, List<GraphEdge> edges) {
}
