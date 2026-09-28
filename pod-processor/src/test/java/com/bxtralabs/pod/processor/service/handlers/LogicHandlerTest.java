package com.bxtralabs.pod.processor.service.handlers;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LogicHandlerTest {

    private final LogicHandler handler = new LogicHandler();

    private static GraphNode node(String type) {
        return new GraphNode("b", "action", "Logic", "item", "Branch", type, Map.of(), null, "app_logic", null, null);
    }

    private static Map<String, Object> path(String id, String name, Object left, String op, Object right) {
        return Map.of("id", id, "name", name, "match", "all",
                "conditions", List.of(Map.of("left", left, "op", op, "right", right)));
    }

    private static final List<Map<String, Object>> PATHS = List.of(
            path("p_vip", "VIP", 1500, "gt", "1000"),
            path("p_big", "Big", 1500, "gt", "100"),
            path("p_eu", "EU", "US", "in_list", "DE, FR"));

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> paths(Map<String, Object> output) {
        return (Map<String, Boolean>) output.get("paths");
    }

    @Test
    void switchTakesOnlyTheFirstMatch() throws Exception {
        Map<String, Object> out = handler.execute(node("logic.switch"), Map.of("paths", PATHS));
        assertEquals(Map.of("p_vip", true, "p_big", false, "p_eu", false, "otherwise", false), paths(out));
        assertEquals(List.of("VIP"), out.get("matched"));
    }

    @Test
    void switchFallsBackToOtherwise() throws Exception {
        Map<String, Object> out = handler.execute(node("logic.switch"), Map.of("paths", List.of(PATHS.get(2))));
        assertEquals(Map.of("p_eu", false, "otherwise", true), paths(out));
        assertEquals(List.of("Otherwise"), out.get("matched"));
    }

    @Test
    void pathsTakesEveryMatch() throws Exception {
        Map<String, Object> out = handler.execute(node("logic.paths"), Map.of("paths", PATHS));
        assertEquals(Map.of("p_vip", true, "p_big", true, "p_eu", false), paths(out));
        assertEquals(List.of("VIP", "Big"), out.get("matched"));
    }

    @Test
    void ifElseAndFilter() throws Exception {
        Map<String, Object> yes = Map.of("match", "all", "conditions", List.of(Map.of("left", "paid", "op", "equals", "right", "PAID")));
        Map<String, Object> no = Map.of("match", "all", "conditions", List.of(Map.of("left", "open", "op", "equals", "right", "paid")));
        assertEquals(Map.of("if", true, "else", false), paths(handler.execute(node("logic.if_else"), Map.of("conditions", yes))));
        assertEquals(Map.of("if", false, "else", true), paths(handler.execute(node("logic.if_else"), Map.of("conditions", no))));
        assertEquals(Map.of("pass", true), paths(handler.execute(node("logic.filter"), Map.of("conditions", yes))));
        Map<String, Object> stopped = handler.execute(node("logic.filter"), Map.of("conditions", no));
        assertEquals(Map.of("pass", false), paths(stopped));
        assertEquals(List.of(), stopped.get("matched"));
    }

    @Test
    void theOutputExplainsTheDecision() throws Exception {
        Map<String, Object> out = handler.execute(node("logic.switch"), Map.of("paths", PATHS));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> explain = (List<Map<String, Object>>) out.get("explain");
        assertEquals(true, explain.get(0).get("matched"));
        assertEquals(1500, ((List<Map<String, Object>>) explain.get(0).get("conditions")).getFirst().get("left"));
        assertEquals(false, explain.get(1).get("checked"), "a Switch doesn't check paths after the first match");
    }
}
