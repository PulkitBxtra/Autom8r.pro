package com.bxtralabs.pod.processor.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Copy of pod-backend's GraphEdge; keep the two in sync.
// condition null means the edge is always taken.
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphEdge(String from, String to, String condition) {
}
