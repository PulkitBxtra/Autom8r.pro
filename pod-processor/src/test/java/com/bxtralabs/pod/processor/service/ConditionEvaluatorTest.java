package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.graph.GraphEdge;
import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.template.TemplateResolver;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator =
            new ConditionEvaluator(new TemplateResolver(JsonMapper.builder().build()));

    private static Map<String, Object> contextWithBody(Map<String, Object> body) {
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("body", body);
        Map<String, Object> context = new HashMap<>();
        context.put("trigger", trigger);
        context.put("steps", Map.of());
        return context;
    }

    private boolean taken(String condition, Map<String, Object> body) {
        return evaluator.isTaken(new GraphEdge("a", "b", condition, null), null, contextWithBody(body));
    }

    @Test
    void noConditionMeansAlwaysTaken() {
        assertTrue(taken(null, Map.of()));
        assertTrue(taken("   ", Map.of()));
    }

    @Test
    void truthyValuesTakeTheEdge() {
        assertTrue(taken("{{trigger.body.v}}", Map.of("v", true)));
        assertTrue(taken("{{trigger.body.v}}", Map.of("v", 1)));
        assertTrue(taken("{{trigger.body.v}}", Map.of("v", "yes")));
        assertTrue(taken("{{trigger.body.v}}", Map.of("v", List.of(1))));
        assertTrue(taken("{{trigger.body.v}}", Map.of("v", Map.of("k", 1))));
    }

    @Test
    void falsyValuesSkipTheEdge() {
        assertFalse(taken("{{trigger.body.v}}", Map.of("v", false)));
        assertFalse(taken("{{trigger.body.v}}", Map.of("v", 0)));
        assertFalse(taken("{{trigger.body.v}}", Map.of("v", 0.0)));
        assertFalse(taken("{{trigger.body.v}}", Map.of("v", "")));
        assertFalse(taken("{{trigger.body.v}}", Map.of("v", "false")));
        assertFalse(taken("{{trigger.body.v}}", Map.of("v", "FALSE")));
        assertFalse(taken("{{trigger.body.v}}", Map.of("v", "0")));
        assertFalse(taken("{{trigger.body.v}}", Map.of("v", List.of())));
        assertFalse(taken("{{trigger.body.missing}}", Map.of()));
    }

    @Test
    void literalConditionsWithoutTemplates() {
        assertTrue(taken("true", Map.of()));
        assertFalse(taken("false", Map.of()));
    }

    // A Logic step's edges follow its output: paths[sourceHandle] (a Filter's is "pass").
    @Test
    void edgesOutOfALogicStepFollowItsChosenPaths() {
        GraphNode branch = new GraphNode("br", "action", "Logic", "i", "Switch", "logic.switch", Map.of(), null, "app_logic", null, null);
        GraphNode filter = new GraphNode("br", "action", "Logic", "i", "Filter", "logic.filter", Map.of(), null, "app_logic", null, null);
        Map<String, Object> context = contextWithBody(Map.of());
        context.put("steps", Map.of("br", Map.of("output", Map.of("paths", Map.of("p_vip", true, "otherwise", false, "pass", true)))));

        assertTrue(evaluator.isTaken(new GraphEdge("br", "x", null, "p_vip"), branch, context));
        assertFalse(evaluator.isTaken(new GraphEdge("br", "x", null, "otherwise"), branch, context));
        assertFalse(evaluator.isTaken(new GraphEdge("br", "x", null, "p_gone"), branch, context), "an unknown path is never taken");
        assertTrue(evaluator.isTaken(new GraphEdge("br", "x", null, null), filter, context));
    }
}
