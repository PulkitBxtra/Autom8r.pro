package com.bxtralabs.pod.backend.model.graph;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowGraphJsonTest {

    // Versions saved before a field rule gained "secret" still load, as not secret.
    @Test
    void fieldRulesSavedBeforeSecretExistedStillRead() {
        JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        WorkflowGraph graph = mapper.readValue("""
                {"nodes": [{"id": "a", "kind": "action", "fields": [{"key": "url", "label": "URL", "type": "text", "required": true}]}],
                 "edges": []}
                """, WorkflowGraph.class);
        FieldSpec url = graph.nodes().getFirst().fields().getFirst();
        assertTrue(url.required());
        assertFalse(url.secret());
    }
}
