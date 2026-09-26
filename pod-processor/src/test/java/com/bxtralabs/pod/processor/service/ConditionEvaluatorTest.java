package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.graph.GraphEdge;
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
        return evaluator.isTaken(new GraphEdge("a", "b", condition), contextWithBody(body));
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
}
