package com.bxtralabs.pod.processor.model.graph;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowGraphJsonTest {

    // Even with a mapper that rejects unknown properties (as the one reading jsonb columns does),
    // a graph written by a newer pod-backend still reads.
    @Test
    void propertiesAddedByANewerBackendAreIgnored() {
        JsonMapper strict = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        String json = """
                {"nodes": [{"id": "a", "kind": "action", "type": "http_request", "someday": 1,
                            "position": {"x": 1, "y": 2, "z": 3},
                            "fields": [{"key": "url", "label": "URL", "type": "text", "required": true, "newRule": "x"}]}],
                 "edges": [{"from": "t", "to": "a", "priority": 5}], "layoutVersion": 2}
                """;
        WorkflowGraph graph = strict.readValue(json, WorkflowGraph.class);
        assertEquals("http_request", graph.nodes().getFirst().type());
        assertEquals("url", graph.nodes().getFirst().fields().getFirst().key());
        assertEquals("a", graph.edges().getFirst().to());
        // ...and so does one written before "secret" existed on field rules.
        assertFalse(graph.nodes().getFirst().fields().getFirst().secret());
    }
}
