package com.bxtralabs.pod.backend.catalog;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CatalogServiceTest {

    private static CatalogService real() throws Exception {
        return new CatalogService(JsonMapper.builder().build());
    }

    @Test
    void theShippedCatalogLoads() throws Exception {
        CatalogService catalog = real();

        assertTrue(catalog.apps().size() >= 10);
        CatalogApp.Action http = catalog.action("act_http_request").orElseThrow();
        assertEquals("http_request", http.handler(), "must match pod-processor's HttpRequestHandler.TYPE");
        assertEquals("app_http", catalog.appOf("act_http_request").orElseThrow().id());
        assertEquals("app_webhook", catalog.appOf("trg_webhook_catch").orElseThrow().id());
        assertTrue(catalog.appOf("act_http_request").orElseThrow().connectionOptional());
        assertFalse(catalog.appOf("act_github_comment").orElseThrow().connectionOptional());
    }

    // Saved workflows point at these ids, so they must keep resolving.
    @Test
    void idsUsedBySavedWorkflowsStillExist() throws Exception {
        CatalogService catalog = real();
        Stream.of("trg_gmail_new_email", "trg_gmail_new_attachment", "trg_gmail_new_label",
                "trg_slack_new_message", "trg_slack_new_mention", "trg_sheets_new_row", "trg_sheets_updated_row",
                "trg_github_new_issue", "trg_github_new_pr", "trg_notion_new_page", "trg_notion_updated_db",
                "trg_stripe_new_payment", "trg_stripe_new_customer", "trg_discord_new_message",
                "trg_trello_new_card", "trg_trello_card_moved")
                .forEach(id -> assertTrue(catalog.trigger(id).isPresent(), id));
        Stream.of("act_gmail_send", "act_gmail_draft", "act_slack_post", "act_slack_dm", "act_sheets_add_row",
                "act_sheets_update_row", "act_github_create_issue", "act_github_comment", "act_notion_create_page",
                "act_notion_update_page", "act_stripe_create_invoice", "act_stripe_refund", "act_discord_post",
                "act_trello_create_card", "act_trello_move_card")
                .forEach(id -> assertTrue(catalog.action(id).isPresent(), id));
    }

    @Test
    void everyActionHasAHandlerAndEveryFieldAKnownType() throws Exception {
        for (CatalogApp app : real().apps()) {
            for (CatalogApp.Action a : app.actions()) {
                assertFalse(a.handler().isBlank(), a.id());
                a.fields().forEach(f -> assertTrue(CatalogField.TYPES.contains(f.type()), a.id() + "." + f.key()));
            }
        }
    }

    private static CatalogField field(String key, String type, List<CatalogField.Option> options, Object dflt) {
        return new CatalogField(key, "Label", type, false, null, null, options, dflt);
    }

    private static List<CatalogApp> appWith(String handler, CatalogField... fields) {
        return List.of(new CatalogApp("app_x", "X", null, null, List.of(),
                List.of(new CatalogApp.Action("act_x", "Do X", null, handler, List.of(fields)))));
    }

    private static void assertInvalid(List<CatalogApp> apps, String expected) {
        Exception e = assertThrows(IllegalStateException.class, () -> new CatalogService(apps));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    @Test
    void brokenCatalogsAreRejectedAtStartup() {
        assertInvalid(List.of(), "no apps");
        assertInvalid(appWith(null), "without a handler");
        assertInvalid(appWith("h", field("a", "colour", null, null)), "unknown type colour");
        assertInvalid(appWith("h", field("a", "text", null, null), field("a", "text", null, null)), "twice");
        assertInvalid(appWith("h", field("bad key", "text", null, null)), "bad key");
        assertInvalid(appWith("h", field("a", "select", null, null)), "without options");
        assertInvalid(appWith("h", field("a", "text", List.of(new CatalogField.Option("x", "X")), null)), "non-select");
        assertInvalid(appWith("h", field("a", "select", List.of(new CatalogField.Option("x", "X")), "y")), "default");

        List<CatalogApp> twice = List.of(
                new CatalogApp("app_a", "A", null, null, List.of(new CatalogApp.Trigger("trg_same", "T", null, List.of())), List.of()),
                new CatalogApp("app_b", "B", null, null, List.of(new CatalogApp.Trigger("trg_same", "T", null, List.of())), List.of()));
        assertInvalid(twice, "item trg_same twice");
    }

    @Test
    void theCatalogServesJsonTheFrontendCanRead() throws Exception {
        String json = JsonMapper.builder().build().writeValueAsString(real().apps());
        List<?> apps = JsonMapper.builder().build().readValue(json, List.class);
        Map<?, ?> http = (Map<?, ?>) apps.stream().filter(a -> "app_http".equals(((Map<?, ?>) a).get("id"))).findFirst().orElseThrow();
        Map<?, ?> action = (Map<?, ?>) ((List<?>) http.get("actions")).getFirst();
        Map<?, ?> method = (Map<?, ?>) ((List<?>) action.get("fields")).getFirst();
        assertEquals("GET", method.get("defaultValue"));
        assertEquals(true, method.get("required"));
        assertEquals(5, ((List<?>) method.get("options")).size());
    }
}
