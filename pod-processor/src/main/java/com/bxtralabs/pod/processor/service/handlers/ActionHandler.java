package com.bxtralabs.pod.processor.service.handlers;

import com.bxtralabs.pod.processor.model.graph.GraphNode;

import java.util.Map;

// Does the actual work of one action step. input is the node's parameters with templates
// already resolved. Throw to fail the step; the exception message becomes the step's error.
public interface ActionHandler {

    boolean supports(GraphNode node);

    Map<String, Object> execute(GraphNode node, Map<String, Object> input) throws Exception;

    // With the step's connection (null when it has none). Handlers that call an app override
    // this; the rest ignore credentials.
    default Map<String, Object> execute(GraphNode node, Map<String, Object> input, StepCredentials credentials)
            throws Exception {
        return execute(node, input);
    }
}
