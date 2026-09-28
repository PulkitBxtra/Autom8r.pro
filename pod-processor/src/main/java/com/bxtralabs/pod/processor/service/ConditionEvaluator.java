package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.graph.GraphEdge;
import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.LogicHandler;
import com.bxtralabs.pod.processor.service.template.TemplateResolver;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

// Decides whether a finished step's edge to a child is taken.
// Out of a Logic step (Switch, Paths, If/Else, Filter): only if the step's output marks the
// edge's path as taken, output.paths[sourceHandle] (a Filter's single output is "pass").
// An edge may also carry an older-style condition: a template checked for truthiness, e.g.
// "{{trigger.body.urgent}}". Both must hold.
@Component
public class ConditionEvaluator {

    private final TemplateResolver templateResolver;

    public ConditionEvaluator(TemplateResolver templateResolver) {
        this.templateResolver = templateResolver;
    }

    public boolean isTaken(GraphEdge edge, GraphNode source, Map<String, Object> context) {
        if (source != null && LogicHandler.isLogic(source.type())) {
            String output = edge.sourceHandle() == null ? "pass" : edge.sourceHandle();
            Object taken = TemplateResolver.lookup("steps." + edge.from() + ".output.paths." + output, context);
            if (!Boolean.TRUE.equals(taken)) {
                return false;
            }
        }
        if (edge.condition() == null || edge.condition().isBlank()) {
            return true;
        }
        return truthy(templateResolver.resolve(edge.condition(), context));
    }

    // false: null, false, 0, empty/blank text, "false", "0", "null", empty list/map. Everything else is true.
    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof String s) {
            String t = s.trim();
            return !(t.isEmpty() || t.equalsIgnoreCase("false") || t.equals("0") || t.equalsIgnoreCase("null"));
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }
}
