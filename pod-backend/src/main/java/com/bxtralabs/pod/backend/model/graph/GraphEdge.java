package com.bxtralabs.pod.backend.model.graph;

// A dependency: "to" runs after "from" finishes.
// condition: optional template checked for truthiness (older graphs; the editor no longer sets
// it, branching is done with Logic steps); null means the edge is always taken.
// sourceHandle: which output of the source step the edge leaves from. Only Logic steps have
// several (one per path, e.g. a Switch's "p_1a2b3c" or "otherwise"); null everywhere else.
public record GraphEdge(String from, String to, String condition, String sourceHandle) {
}
