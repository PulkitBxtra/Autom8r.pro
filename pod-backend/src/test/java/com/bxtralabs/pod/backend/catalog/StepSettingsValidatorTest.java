package com.bxtralabs.pod.backend.catalog;

import com.bxtralabs.pod.backend.model.graph.GraphEdge;
import com.bxtralabs.pod.backend.model.graph.GraphNode;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StepSettingsValidatorTest {

    private final StepSettingsValidator validator;

    StepSettingsValidatorTest() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        validator = new StepSettingsValidator(new CatalogService(mapper), mapper);
    }

    private static final GraphNode WEBHOOK = new GraphNode("t", "trigger", "Webhook", "trg_webhook_catch", null,
            null, null, null, null, null, null);

    private GraphNode check(String itemId, Map<String, Object> parameters) {
        GraphNode node = new GraphNode("a", "action", "x", itemId, null, null, parameters, null, null, null, null);
        return validator.normalize(new WorkflowGraph(List.of(WEBHOOK, node), List.of(new GraphEdge("t", "a", null))))
                .nodes().get(1);
    }

    private String rejected(String itemId, Map<String, Object> parameters) {
        return assertThrows(IllegalArgumentException.class, () -> check(itemId, parameters)).getMessage();
    }

    private static Map<String, Object> http(Object... kv) {
        Map<String, Object> m = new HashMap<>(Map.of("method", "GET", "url", "https://example.com"));
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void numbersAcceptNumbersNumericTextAndTemplates() {
        assertEquals(15, ((Number) check("act_http_request", http("timeoutSeconds", 15)).parameters().get("timeoutSeconds")).intValue());
        assertEquals(20L, check("act_http_request", http("timeoutSeconds", " 20 ")).parameters().get("timeoutSeconds"));
        assertEquals(2.5, check("act_http_request", http("timeoutSeconds", "2.5")).parameters().get("timeoutSeconds"));
        assertEquals("{{trigger.body.t}}", check("act_http_request", http("timeoutSeconds", "{{trigger.body.t}}")).parameters().get("timeoutSeconds"));
        assertEquals("Step \"Make a Request\": Timeout (seconds) must be a number", rejected("act_http_request", http("timeoutSeconds", "soon")));
    }

    @Test
    void selectsOnlyTakeTheirOptions() {
        assertEquals("POST", check("act_http_request", http("method", "POST")).parameters().get("method"));
        assertTrue(rejected("act_http_request", http("method", "TRACE")).contains("must be one of: GET, POST, PUT, PATCH, DELETE"));
    }

    @Test
    void typesAreEnforced() {
        assertTrue(rejected("act_http_request", http("url", 42)).endsWith("URL must be text"));
        assertTrue(rejected("act_http_request", http("headers", List.of("a"))).endsWith("must be a list of names and values"));
        assertTrue(rejected("act_http_request", http("headers", Map.of("X", Map.of("nested", 1)))).endsWith("must be a list of names and values"));
        assertTrue(rejected("act_stripe_create_invoice", Map.of("customerId", "c", "amount", 1, "currency", "usd", "send", "yes"))
                .endsWith("must be true or false"));
        // json takes any JSON value
        assertEquals(List.of(1, 2), check("act_http_request", http("body", List.of(1, 2))).parameters().get("body"));
    }

    @Test
    void requiredMeansNotEmpty() {
        assertEquals("Step \"Make a Request\" needs URL", rejected("act_http_request", Map.of("method", "GET", "url", "  ")));
        assertEquals("Step \"Add Row\" needs Values", rejected("act_sheets_add_row", Map.of("spreadsheetId", "s", "sheet", "S", "values", Map.of())));
    }

    @Test
    void unknownEventsAndMismatchedAppsAreRejected() {
        assertTrue(rejected("act_retired", Map.of()).contains("isn't available any more"));
        GraphNode wrongApp = new GraphNode("a", "action", "Slack", "act_http_request", null, null, http(), null, "app_slack", null, null);
        Exception e = assertThrows(IllegalArgumentException.class, () -> validator.normalize(
                new WorkflowGraph(List.of(WEBHOOK, wrongApp), List.of(new GraphEdge("t", "a", null)))));
        assertTrue(e.getMessage().contains("doesn't belong to the app"), e.getMessage());
    }

    @Test
    void settingsHaveASizeLimit() {
        assertTrue(rejected("act_http_request", http("body", "x".repeat(70_000))).contains("over 64 KB"));
    }

    @Test
    void triggersAreCheckedToo() {
        GraphNode repoless = new GraphNode("t", "trigger", "GitHub", "trg_github_new_issue", null, null, Map.of(), null, null, null, null);
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> validator.normalize(new WorkflowGraph(List.of(repoless), List.of())));
        assertEquals("Step \"New Issue\" needs Repository", e.getMessage());
        assertNull(validator.normalize(new WorkflowGraph(List.of(WEBHOOK), List.of())).nodes().getFirst().type(), "triggers have no handler");
    }
}
