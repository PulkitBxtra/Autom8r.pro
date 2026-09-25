package com.bxtralabs.pod.backend.model.graph;

// A dependency: "to" runs after "from" finishes.
// condition is optional and not evaluated yet; null means the edge is always taken.
public record GraphEdge(String from, String to, String condition) {
}
