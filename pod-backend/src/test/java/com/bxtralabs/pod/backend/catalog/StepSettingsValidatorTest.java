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
        // With an account, so apps that need one get past that check to the one under test.
        GraphNode node = new GraphNode("a", "action", "x", itemId, null, null, parameters, null, null, "con_test", null);
        return validator.normalize(new WorkflowGraph(List.of(WEBHOOK, node), List.of(new GraphEdge("t", "a", null, null))))
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
                new WorkflowGraph(List.of(WEBHOOK, wrongApp), List.of(new GraphEdge("t", "a", null, null)))));
        assertTrue(e.getMessage().contains("doesn't belong to the app"), e.getMessage());
    }

    @Test
    void settingsHaveASizeLimit() {
        assertTrue(rejected("act_http_request", http("body", "x".repeat(70_000))).contains("over 64 KB"));
    }

    @Test
    void triggersAreCheckedToo() {
        GraphNode repoless = new GraphNode("t", "trigger", "GitHub", "trg_github_new_issue", null, null, Map.of(), null, null, "con_test", null);
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> validator.normalize(new WorkflowGraph(List.of(repoless), List.of())));
        assertEquals("Step \"New Issue\" needs Repository", e.getMessage());
        assertNull(validator.normalize(new WorkflowGraph(List.of(WEBHOOK), List.of())).nodes().getFirst().type(), "triggers have no handler");
    }

    // ---- Logic steps ----

    private static Map<String, Object> cond(Object left, String op, Object right) {
        Map<String, Object> c = new HashMap<>();
        c.put("left", left);
        c.put("op", op);
        c.put("right", right);
        return c;
    }

    private static Map<String, Object> path(String id, String name, Map<String, Object>... conditions) {
        return Map.of("id", id, "name", name, "match", "all", "conditions", List.of(conditions));
    }

    private WorkflowGraph logicGraph(String itemId, Map<String, Object> params, GraphEdge... fromBranch) {
        GraphNode branch = new GraphNode("b", "action", "Logic", itemId, null, null, params, null, null, null, null);
        GraphNode next = new GraphNode("n", "action", "HTTP", "act_http_request", null, null, http(), null, null, null, null);
        List<GraphEdge> edges = new java.util.ArrayList<>(List.of(new GraphEdge("t", "b", null, null)));
        edges.addAll(List.of(fromBranch));
        return new WorkflowGraph(List.of(WEBHOOK, branch, next), edges);
    }

    private static final Map<String, Object> SWITCH = Map.of("paths", List.of(
            path("p_vip", "VIP", cond("{{trigger.body.amount}}", "gt", "1000")),
            path("p_eu", "EU", cond("{{trigger.body.country}}", "in_list", "DE, FR"), cond("{{trigger.body.x}}", "is_empty", null))));

    @Test
    void aSwitchSavesWithEdgesFromItsPathsAndOtherwise() {
        WorkflowGraph saved = validator.normalize(logicGraph("act_logic_switch", SWITCH,
                new GraphEdge("b", "n", null, "p_vip"), new GraphEdge("b", "n", null, "otherwise")));
        assertEquals("logic.switch", saved.nodes().get(1).type());
        assertEquals(List.of("p_vip", "otherwise"), saved.edges().subList(1, 3).stream().map(GraphEdge::sourceHandle).toList());
    }

    @Test
    void edgesMustLeaveFromAnOutputTheStepHas() {
        Exception e = assertThrows(IllegalArgumentException.class, () -> validator.normalize(
                logicGraph("act_logic_switch", SWITCH, new GraphEdge("b", "n", null, "p_gone"))));
        assertEquals("A connection leaves \"Switch\" from a path it doesn't have; connect it to one of its paths", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> validator.normalize(
                logicGraph("act_logic_switch", SWITCH, new GraphEdge("b", "n", null, null))));
        assertThrows(IllegalArgumentException.class, () -> validator.normalize(
                logicGraph("act_logic_paths", SWITCH, new GraphEdge("b", "n", null, "otherwise"))), "Paths has no Otherwise");
    }

    @Test
    void filterEdgesNeedNoNameAndOtherStepsLoseAnyTheyAreSent() {
        Map<String, Object> filter = Map.of("conditions", Map.of("match", "any", "conditions", List.of(cond("{{trigger.body.ok}}", "is_true", null))));
        WorkflowGraph saved = validator.normalize(logicGraph("act_logic_filter", filter, new GraphEdge("b", "n", null, null)));
        assertEquals("pass", saved.edges().get(1).sourceHandle());
        // An ordinary step's edge never carries an output name.
        GraphNode http = new GraphNode("a", "action", "HTTP", "act_http_request", null, null, http(), null, null, null, null);
        WorkflowGraph plain = validator.normalize(new WorkflowGraph(List.of(WEBHOOK, http), List.of(new GraphEdge("t", "a", null, "p_x"))));
        assertNull(plain.edges().getFirst().sourceHandle());
    }

    @Test
    void conditionsAreChecked() {
        assertTrue(rejected("act_logic_if_else", Map.of("conditions", Map.of("match", "all", "conditions", List.of(cond("", "equals", "x")))))
                .endsWith("If has a condition with nothing to check"));
        assertTrue(rejected("act_logic_if_else", Map.of("conditions", Map.of("match", "all", "conditions", List.of(cond("a", "equals", " ")))))
                .endsWith("nothing to compare to"));
        assertTrue(rejected("act_logic_if_else", Map.of("conditions", Map.of("match", "all", "conditions", List.of(cond("a", "matches", "x")))))
                .contains("unknown comparison: matches"));
        assertTrue(rejected("act_logic_if_else", Map.of("conditions", Map.of("match", "most", "conditions", List.of(cond("a", "is_empty", null)))))
                .contains("match must be all or any"));
        assertEquals("Step \"If / Else\" needs If", rejected("act_logic_if_else", Map.of("conditions", Map.of())));
    }

    @Test
    void pathsNeedUniqueIdsAndNames() {
        assertTrue(rejected("act_logic_paths", Map.of("paths", List.of(path("p_a", "A", cond("x", "is_empty", null)), path("p_a", "B", cond("x", "is_empty", null)))))
                .contains("repeated path id"));
        assertTrue(rejected("act_logic_paths", Map.of("paths", List.of(path("p_a", " ", cond("x", "is_empty", null)))))
                .contains("every path needs a name"));
        assertEquals("Step \"Paths\" needs Paths", rejected("act_logic_paths", Map.of("paths", List.of())));
    }

    @Test
    void theCatalogDefaultStillNeedsFillingIn() {
        // A new Switch starts with one empty condition: saving it untouched asks for the data to check.
        assertTrue(rejected("act_logic_switch", Map.of("paths", List.of(path("p_a", "Path A", cond("", "equals", "")))))
                .contains("nothing to check"));
    }

    // ---- accounts ----

    private GraphNode normalizedWith(String itemId, Map<String, Object> params, String connectionId) {
        GraphNode node = new GraphNode("a", "action", "x", itemId, null, null, params, null, null, connectionId, null);
        return validator.normalize(new WorkflowGraph(List.of(WEBHOOK, node), List.of(new GraphEdge("t", "a", null, null))))
                .nodes().get(1);
    }

    @Test
    void appsThatNeedAnAccountCantBeSavedWithoutOne() {
        Exception e = assertThrows(IllegalArgumentException.class, () -> normalizedWith("act_github_create_issue",
                Map.of("repository", "o/r", "title", "t"), null));
        assertEquals("Step \"Create Issue\" needs a GitHub account; choose one in the step's setup", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> normalizedWith("act_github_create_issue",
                Map.of("repository", "o/r", "title", "t"), " "));
        // A GitHub trigger too.
        GraphNode trigger = new GraphNode("t", "trigger", "GitHub", "trg_github_new_issue", null, null,
                Map.of("repository", "o/r"), null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> validator.normalize(new WorkflowGraph(List.of(trigger), List.of())));
    }

    @Test
    void httpMayHaveAnAccountAndLogicNever() {
        assertNull(normalizedWith("act_http_request", http(), null).connectionId());
        assertEquals("con_1", normalizedWith("act_http_request", http(), "con_1").connectionId());
        Map<String, Object> filter = Map.of("conditions", Map.of("match", "all",
                "conditions", List.of(cond("{{trigger.body.ok}}", "is_true", null))));
        assertNull(normalizedWith("act_logic_filter", filter, "con_1").connectionId(), "dropped: Logic steps act through nothing");
    }
}
