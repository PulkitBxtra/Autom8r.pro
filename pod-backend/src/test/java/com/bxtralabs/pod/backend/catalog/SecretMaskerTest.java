package com.bxtralabs.pod.backend.catalog;

import com.bxtralabs.pod.backend.model.graph.FieldSpec;
import com.bxtralabs.pod.backend.model.graph.GraphNode;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.bxtralabs.pod.backend.catalog.SecretMasker.MASK;
import static org.junit.jupiter.api.Assertions.*;

class SecretMaskerTest {

    private final SecretMasker masker;

    SecretMaskerTest() throws Exception {
        masker = new SecretMasker(new CatalogService(JsonMapper.builder().build()));
    }

    private static final List<FieldSpec> HTTP_FIELDS = List.of(
            new FieldSpec("url", "URL", "text", true, null, false),
            new FieldSpec("headers", "Headers", "keyvalue", false, null, true));

    private static GraphNode http(Map<String, Object> headers, List<FieldSpec> fields) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("url", "https://api.example.com");
        if (headers != null) params.put("headers", headers);
        return new GraphNode("a1", "action", "HTTP", "act_http_request", "Make a Request", "http_request", params,
                null, "app_http", null, fields);
    }

    private static WorkflowGraph graph(GraphNode node) {
        return new WorkflowGraph(List.of(node), List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> headersOf(WorkflowGraph g) {
        return (Map<String, Object>) g.nodes().getFirst().parameters().get("headers");
    }

    @Test
    void sensitiveHeaderValuesAreHiddenOthersAndTemplatesStay() {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer sk_live_123");
        headers.put("X-Api-Key", "abc");
        headers.put("Accept", "application/json");
        headers.put("X-Session-Token", "{{trigger.body.token}}");

        Map<String, Object> shown = headersOf(masker.mask(graph(http(headers, HTTP_FIELDS))));
        assertEquals(MASK, shown.get("Authorization"));
        assertEquals(MASK, shown.get("X-Api-Key"));
        assertEquals("application/json", shown.get("Accept"));
        assertEquals("{{trigger.body.token}}", shown.get("X-Session-Token"), "a template only says where data comes from");
        assertEquals("https://api.example.com", masker.mask(graph(http(headers, HTTP_FIELDS))).nodes().getFirst().parameters().get("url"));
    }

    @Test
    void runInputHidesEverySecretValueIncludingFilledInTemplates() {
        Map<String, Object> input = Map.of("url", "https://api.example.com",
                "headers", Map.of("X-Session-Token", "tok_from_trigger", "Accept", "json"));
        @SuppressWarnings("unchecked")
        Map<String, Object> shown = (Map<String, Object>) masker.maskInput(http(null, HTTP_FIELDS), input).get("headers");
        assertEquals(MASK, shown.get("X-Session-Token"));
        assertEquals("json", shown.get("Accept"));
    }

    @Test
    void stepsSavedBeforeStampingGoByTheCatalog() {
        Map<String, Object> shown = headersOf(masker.mask(graph(http(Map.of("Authorization", "Bearer x"), null))));
        assertEquals(MASK, shown.get("Authorization"));
    }

    @Test
    void savingWithTheMaskKeepsTheStoredValue() {
        WorkflowGraph stored = graph(http(Map.of("Authorization", "Bearer old", "Accept", "json"), HTTP_FIELDS));
        Map<String, Object> edited = new LinkedHashMap<>();
        edited.put("Authorization", MASK);
        edited.put("Accept", "text/plain");
        edited.put("X-Api-Key", "new-key");

        Map<String, Object> saved = headersOf(masker.restore(graph(http(edited, HTTP_FIELDS)), stored));
        assertEquals("Bearer old", saved.get("Authorization"));
        assertEquals("text/plain", saved.get("Accept"));
        assertEquals("new-key", saved.get("X-Api-Key"));
    }

    @Test
    void aMaskWithNothingStoredBehindItIsRejected() {
        WorkflowGraph withMask = graph(http(Map.of("Authorization", MASK), HTTP_FIELDS));
        Exception e = assertThrows(IllegalArgumentException.class, () -> masker.restore(withMask, null));
        assertEquals("Step \"Make a Request\": enter headers Authorization again; the saved value isn't available", e.getMessage());
        // Renaming the header loses its value too.
        WorkflowGraph stored = graph(http(Map.of("Authorization", "Bearer old"), HTTP_FIELDS));
        assertThrows(IllegalArgumentException.class,
                () -> masker.restore(graph(http(Map.of("Proxy-Authorization", MASK), HTTP_FIELDS)), stored));
    }

    @Test
    void sensitiveNames() {
        for (String k : List.of("Authorization", "cookie", "X-API-KEY", "api_key", "X-Auth-Token", "client_secret", "password", "Stripe-Signature"))
            assertTrue(SecretMasker.isSensitiveKey(k), k);
        for (String k : List.of("Accept", "Content-Type", "User-Agent", "X-Request-Id"))
            assertFalse(SecretMasker.isSensitiveKey(k), k);
    }
}
