package com.bxtralabs.pod.processor.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Copy of pod-backend's GraphEdge; keep the two in sync.
// condition null means the edge is always taken.
@JsonIgnoreProperties(ignoreUnknown = true)
// sourceHandle: which output of the source step the edge leaves from. Only Logic steps have
// several (one per path, e.g. a Switch's "p_1a2b3c" or "otherwise"); null everywhere else.
public record GraphEdge(String from, String to, String condition, String sourceHandle) {
}
