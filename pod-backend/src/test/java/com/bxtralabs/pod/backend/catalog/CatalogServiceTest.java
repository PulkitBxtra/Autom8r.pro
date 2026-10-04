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
        assertEquals("optional", catalog.appOf("act_http_request").orElseThrow().connection());
        assertEquals("required", catalog.appOf("act_github_comment").orElseThrow().connection());
        assertEquals("none", catalog.appOf("act_logic_switch").orElseThrow().connection());
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

    // Everything that runs for real lists what it returns, so the data picker can offer it.
    @Test
    void realTriggersAndActionsDeclareTheirOutputs() throws Exception {
        CatalogService catalog = real();
        Stream.of("trg_github_new_issue", "trg_github_new_pr", "trg_slack_new_message", "trg_slack_new_mention")
                .forEach(id -> assertFalse(catalog.trigger(id).orElseThrow().outputs().isEmpty(), id));
        Stream.of("act_http_request", "act_github_create_issue", "act_github_comment", "act_slack_post", "act_slack_dm",
                        "act_notion_create_page", "act_notion_update_page", "act_logic_switch", "act_logic_paths",
                        "act_logic_if_else", "act_logic_filter")
                .forEach(id -> assertFalse(catalog.action(id).orElseThrow().outputs().isEmpty(), id));
        assertTrue(catalog.trigger("trg_webhook_catch").orElseThrow().outputs().isEmpty(),
                "a webhook starts with whatever its caller sends");
        assertEquals(List.of("number", "url", "id", "title", "state", "labels"), catalog.action("act_github_create_issue")
                .orElseThrow().outputs().stream().map(CatalogOutput::key).toList(), "what GitHubHandler returns");
        assertEquals(List.of("id", "channelId", "content", "timestamp"), catalog.action("act_discord_post")
                .orElseThrow().outputs().stream().map(CatalogOutput::key).toList(), "what DiscordHandler returns");
        for (String trello : List.of("act_trello_create_card", "act_trello_move_card")) {
            assertEquals(List.of("id", "name", "url", "listId", "boardId"), catalog.action(trello)
                    .orElseThrow().outputs().stream().map(CatalogOutput::key).toList(), "what TrelloHandler returns");
        }
        assertEquals(List.of("id", "number", "status", "url", "amountDue", "currency", "customerId"), catalog.action("act_stripe_create_invoice")
                .orElseThrow().outputs().stream().map(CatalogOutput::key).toList(), "what StripeHandler returns");
        assertEquals(List.of("id", "status", "amount", "currency", "paymentIntentId"), catalog.action("act_stripe_refund")
                .orElseThrow().outputs().stream().map(CatalogOutput::key).toList(), "what StripeHandler returns");
    }

    // Account-backed choices only on text fields, and only lists of the step's own app's accounts.
    @Test
    void optionListsMustFitTheFieldAndTheApp() throws Exception {
        CatalogField repo = new CatalogField("repository", "Repository", "text", true, null, null, null, null, null, "github.repos");
        List<CatalogApp> github = List.of(new CatalogApp("app_github", "GitHub", null, "required", List.of(),
                List.of(new CatalogApp.Action("act_x", "Do X", null, "h", List.of(repo), null, null, false))));
        assertDoesNotThrow(() -> new CatalogService(github));
        List<CatalogApp> slack = List.of(new CatalogApp("app_slack", "Slack", null, "required", List.of(),
                List.of(new CatalogApp.Action("act_x", "Do X", null, "h", List.of(repo), null, null, false))));
        assertInvalid(slack, "isn't a list of app_slack accounts");
        CatalogField number = new CatalogField("n", "N", "number", false, null, null, null, null, null, "github.repos");
        assertInvalid(List.of(new CatalogApp("app_github", "GitHub", null, "required", List.of(),
                List.of(new CatalogApp.Action("act_x", "Do X", null, "h", List.of(number), null, null, false)))), "only text fields");
        assertEquals("slack.channels", real().trigger("trg_slack_new_message").orElseThrow().fields().getFirst().optionsFrom());
    }

    @Test
    void outputsFromMustNameTheActionsOneOutputsField() throws Exception {
        CatalogField declared = new CatalogField("outputs", "Outputs", "outputs", false, null, null, null, null, null, null);
        CatalogField script = new CatalogField("script", "Script", "code", true, null, null, null, null, null, null);
        assertDoesNotThrow(() -> new CatalogService(List.of(new CatalogApp("app_x", "X", null, null, List.of(),
                List.of(new CatalogApp.Action("act_x", "Do X", null, "h", List.of(script, declared), null, "outputs", false))))));
        assertInvalid(List.of(new CatalogApp("app_x", "X", null, null, List.of(),
                List.of(new CatalogApp.Action("act_x", "Do X", null, "h", List.of(script, declared), null, null, false)))), "without outputsFrom");
        assertInvalid(List.of(new CatalogApp("app_x", "X", null, null, List.of(),
                List.of(new CatalogApp.Action("act_x", "Do X", null, "h", List.of(script), null, "script", false)))), "isn't its one outputs field");
        assertEquals("outputs", real().action("act_code_groovy").orElseThrow().outputsFrom());
    }

    private static List<CatalogApp> appWithOutputs(CatalogOutput... outputs) {
        return List.of(new CatalogApp("app_x", "X", null, null, List.of(),
                List.of(new CatalogApp.Action("act_x", "Do X", null, "h", List.of(), List.of(outputs), null, false))));
    }

    @Test
    void brokenOutputsAreRejectedAtStartup() {
        assertInvalid(appWithOutputs(new CatalogOutput("bad key", "L", "text", null)), "bad key");
        assertInvalid(appWithOutputs(new CatalogOutput("a", "L", "text", null), new CatalogOutput("a", "L", "text", null)), "twice");
        assertInvalid(appWithOutputs(new CatalogOutput("a", null, "text", null)), "without a label");
        assertInvalid(appWithOutputs(new CatalogOutput("a", "L", "colour", null)), "unknown type colour");
        assertInvalid(appWithOutputs(new CatalogOutput("a", "L", "text", List.of(new CatalogOutput("b", "L", "text", null)))),
                "only object and list");
        assertInvalid(appWithOutputs(new CatalogOutput("a", "L", "object", List.of(new CatalogOutput("b", "L", "nope", null)))),
                "act_x output a output b of unknown type nope");
        assertDoesNotThrow(() -> new CatalogService(appWithOutputs(new CatalogOutput("items", "Items", "list",
                List.of(new CatalogOutput("name", "Name", "text", null))))));
    }

    private static CatalogField field(String key, String type, List<CatalogField.Option> options, Object dflt) {
        return new CatalogField(key, "Label", type, false, null, null, options, dflt, null, null);
    }

    private static List<CatalogApp> appWith(String handler, CatalogField... fields) {
        return List.of(new CatalogApp("app_x", "X", null, null, List.of(),
                List.of(new CatalogApp.Action("act_x", "Do X", null, handler, List.of(fields), null, null, false))));
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
                new CatalogApp("app_a", "A", null, null, List.of(new CatalogApp.Trigger("trg_same", "T", null, List.of(), null, false)), List.of()),
                new CatalogApp("app_b", "B", null, null, List.of(new CatalogApp.Trigger("trg_same", "T", null, List.of(), null, false)), List.of()));
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
