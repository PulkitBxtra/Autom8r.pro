package com.bxtralabs.pod.processor.service.handlers;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import org.springframework.stereotype.Component;

import java.util.List;

// Picks the handler for a node. Spring injects the handlers sorted by @Order, and
// SimulatedHandler is last and supports everything, so there is always a match.
@Component
public class ActionHandlerRegistry {

    private final List<ActionHandler> handlers;

    public ActionHandlerRegistry(List<ActionHandler> handlers) {
        this.handlers = handlers;
    }

    public ActionHandler handlerFor(GraphNode node) {
        return handlers.stream()
                .filter(h -> h.supports(node))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No handler for " + node.appName() + " / " + node.type()));
    }
}
