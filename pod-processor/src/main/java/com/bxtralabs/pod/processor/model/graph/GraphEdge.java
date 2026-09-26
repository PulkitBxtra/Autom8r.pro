package com.bxtralabs.pod.processor.model.graph;

// Copy of pod-backend's GraphEdge; keep the two in sync.
// condition null means the edge is always taken.
public record GraphEdge(String from, String to, String condition) {
}
