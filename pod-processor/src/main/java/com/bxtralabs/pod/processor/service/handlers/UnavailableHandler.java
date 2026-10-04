package com.bxtralabs.pod.processor.service.handlers;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

// Fallback for steps with no real handler: catalog actions marked comingSoon (Gmail, Trello, ...),
// which pod-backend no longer lets anyone save, but older workflows may still have. Fails the
// step for good rather than pretending it worked. Ordered last so any real handler wins.
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class UnavailableHandler implements ActionHandler {

    @Override
    public boolean supports(GraphNode node) {
        return true;
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input) throws PermanentStepException {
        throw new PermanentStepException(node.appName() + " \"" + node.name()
                + "\" isn't available yet, so this step did nothing; remove it from the workflow");
    }
}
