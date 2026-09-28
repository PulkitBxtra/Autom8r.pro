package com.bxtralabs.pod.processor.service.handlers;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

// Fallback for catalog apps that have no real integration yet (Gmail, Trello, ...): succeeds
// and echoes the resolved input, marked simulated, so workflows built in the UI still run end
// to end. Replace per app with a real handler as integrations land. Ordered last so any real
// handler wins.
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SimulatedHandler implements ActionHandler {

    @Override
    public boolean supports(GraphNode node) {
        return true;
    }

    // Says whether an account was available (never what it holds), which is what the real
    // handler will need.
    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input, StepCredentials credentials) {
        Map<String, Object> output = execute(node, input);
        output.put("account", credentials == null ? null : Map.of("connectionId", credentials.connectionId(),
                "authType", credentials.authType()));
        return output;
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("simulated", true);
        output.put("app", node.appName());
        output.put("action", node.name());
        output.put("input", input);
        return output;
    }
}
