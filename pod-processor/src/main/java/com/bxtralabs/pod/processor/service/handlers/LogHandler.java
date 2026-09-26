package com.bxtralabs.pod.processor.service.handlers;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

// type "log": writes the step's resolved input to the processor log. Handy for checking
// what data reaches a point in a workflow.
@Component
@Order(1)
public class LogHandler implements ActionHandler {

    public static final String TYPE = "log";

    @Override
    public boolean supports(GraphNode node) {
        return TYPE.equals(node.type());
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input) {
        System.out.println("[log step " + node.id() + "] " + input);
        return Map.of("logged", input);
    }
}
