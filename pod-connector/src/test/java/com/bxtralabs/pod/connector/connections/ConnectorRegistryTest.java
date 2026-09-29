package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.connections.Connector.CredentialField;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

// Runs every connector's token check against a local stand-in for each provider's
// "who am I" endpoint, so the tests need no network and no real tokens.
class ConnectorRegistryTest {

    private HttpServer server;
    private ConnectorRegistry registry;
    // Last request headers/query per provider, to assert the exact auth format each API expects.
    private final Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void record(String provider, HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        m.put("method", ex.getRequestMethod());
        m.put("authorization", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
        m.put("notion-version", String.valueOf(ex.getRequestHeaders().getFirst("Notion-Version")));
        m.put("query", String.valueOf(ex.getRequestURI().getRawQuery()));
        seen.put(provider, m);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            q.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return q;
    }

    @BeforeEach
    void startMockProviders() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/github/user", ex -> {
            record("github", ex);
            boolean ok = "Bearer good".equals(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, ok ? 200 : 401, ok ? "{\"login\":\"octocat\"}" : "{\"message\":\"Bad credentials\"}");
        });
        server.createContext("/slack/auth.test", ex -> {
            record("slack", ex);
            boolean ok = "Bearer good".equals(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 200, ok ? "{\"ok\":true,\"team\":\"Acme\",\"user\":\"autom8r-bot\"}" : "{\"ok\":false,\"error\":\"invalid_auth\"}");
        });
        server.createContext("/notion/users/me", ex -> {
            record("notion", ex);
            boolean ok = "Bearer good".equals(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, ok ? 200 : 401, ok ? "{\"name\":\"Autom8r\",\"bot\":{\"workspace_name\":\"Acme HQ\"}}" : "{}");
        });
        server.createContext("/stripe/balance", ex -> {
            record("stripe", ex);
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if ("Bearer sk_live_good".equals(auth)) respond(ex, 200, "{\"livemode\":true}");
            else if ("Bearer sk_test_good".equals(auth)) respond(ex, 200, "{\"livemode\":false}");
            else respond(ex, 401, "{\"error\":{\"message\":\"Invalid API Key\"}}");
        });
        server.createContext("/discord/users/@me", ex -> {
            record("discord", ex);
            boolean ok = "Bot good".equals(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, ok ? 200 : 401, ok ? "{\"username\":\"autom8r-bot\"}" : "{\"message\":\"401: Unauthorized\"}");
        });
        server.createContext("/trello/members/me", ex -> {
            record("trello", ex);
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            if (!"key1".equals(q.get("key"))) { respond(ex, 401, "invalid key"); return; }
            if ("tok&weird=1".equals(q.get("token"))) respond(ex, 200, "{\"fullName\":\"\",\"username\":\"pb\"}");
            else if ("good".equals(q.get("token"))) respond(ex, 200, "{\"fullName\":\"Pulkit B\",\"username\":\"pb\"}");
            else respond(ex, 401, "invalid token");
        });
        server.createContext("/broken/user", ex -> respond(ex, 500, "{}"));
        server.createContext("/html/user", ex -> {
            ex.sendResponseHeaders(200, 5);
            try (OutputStream os = ex.getResponseBody()) { os.write("<html".getBytes()); }
        });
        server.start();

        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        ProviderHttp http = new ProviderHttp(JsonMapper.builder().build());
        registry = new ConnectorRegistry(ConnectorRegistry.defaults(http, new ConnectorRegistry.Endpoints(
                base + "/github", base + "/slack", base + "/notion", base + "/stripe", base + "/discord", base + "/trello")));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String verify(String appId, Map<String, String> creds) {
        return registry.find(appId).orElseThrow().token().verifier().verify(creds);
    }

    private String rejection(String appId, Map<String, String> creds) {
        return assertThrows(ConnectionVerificationException.class, () -> verify(appId, creds)).getMessage();
    }

    // ---------- the registry itself ----------

    @Test
    void listsEveryAppMatchingTheCatalogIds() {
        assertEquals(List.of("app_github", "app_slack", "app_notion", "app_stripe", "app_discord", "app_trello",
                        "app_gmail", "app_sheets", "app_http"),
                registry.all().stream().map(Connector::appId).toList());
        assertTrue(registry.find("app_nope").isEmpty());
        assertTrue(registry.find(null).isEmpty());
    }

    @Test
    void googleAppsAreOAuthOnly() {
        for (String app : List.of("app_gmail", "app_sheets")) {
            Connector c = registry.find(app).orElseThrow();
            assertNull(c.token(), app + " has no token form");
            assertEquals("google", c.oauthProvider());
        }
        assertEquals("github", registry.find("app_github").orElseThrow().oauthProvider(), "GitHub offers both");
        assertEquals("slack", registry.find("app_slack").orElseThrow().oauthProvider(), "Slack offers both");
    }

    @Test
    void credentialFieldsAreRequiredAndSecretsAreFlagged() {
        for (Connector c : registry.all()) {
            if (c.token() == null) continue;
            assertFalse(c.token().fields().isEmpty(), c.appId());
            for (CredentialField f : c.token().fields()) {
                // The Slack signing secret is only needed for triggers.
                assertEquals(!f.key().equals("signingSecret"), f.required(), c.appId() + "." + f.key());
                assertNotNull(f.label());
            }
        }
        assertTrue(registry.find("app_slack").orElseThrow().token().fields().get(0).secret());
        List<CredentialField> trello = registry.find("app_trello").orElseThrow().token().fields();
        assertFalse(trello.get(0).secret(), "Trello API key is public");
        assertTrue(trello.get(1).secret(), "Trello token is secret");
    }

    // ---------- each provider: good token -> label, bad token -> clear rejection ----------

    @Test
    void github() {
        assertEquals("@octocat", verify("app_github", Map.of("token", "good")));
        assertEquals("Bearer good", seen.get("github").get("authorization"));
        assertTrue(rejection("app_github", Map.of("token", "bad")).contains("GitHub rejected these credentials (HTTP 401)"));
    }

    @Test
    void slackReportsFailuresInsideA200() {
        assertEquals("Acme · @autom8r-bot", verify("app_slack", Map.of("token", "good")));
        assertEquals("POST", seen.get("slack").get("method"));
        assertEquals("Slack rejected this token (invalid_auth)", rejection("app_slack", Map.of("token", "bad")));
    }

    @Test
    void notionSendsItsVersionHeader() {
        assertEquals("Autom8r · Acme HQ", verify("app_notion", Map.of("token", "good")));
        assertEquals("2022-06-28", seen.get("notion").get("notion-version"));
        assertTrue(rejection("app_notion", Map.of("token", "bad")).contains("Notion rejected"));
    }

    @Test
    void stripeLabelsLiveAndTestMode() {
        assertEquals("Stripe (live mode)", verify("app_stripe", Map.of("apiKey", "sk_live_good")));
        assertEquals("Stripe (test mode)", verify("app_stripe", Map.of("apiKey", "sk_test_good")));
        assertTrue(rejection("app_stripe", Map.of("apiKey", "sk_bad")).contains("Stripe rejected"));
    }

    @Test
    void discordUsesTheBotPrefix() {
        assertEquals("autom8r-bot", verify("app_discord", Map.of("token", "good")));
        assertEquals("Bot good", seen.get("discord").get("authorization"));
        assertTrue(rejection("app_discord", Map.of("token", "bad")).contains("Discord rejected"));
    }

    @Test
    void trelloPassesKeyAndTokenAsEncodedQueryParams() {
        assertEquals("Pulkit B", verify("app_trello", Map.of("apiKey", "key1", "token", "good")));
        // A token containing & and = must not break the query string.
        assertEquals("@pb", verify("app_trello", Map.of("apiKey", "key1", "token", "tok&weird=1")));
        String message = rejection("app_trello", Map.of("apiKey", "key1", "token", "leaky-secret"));
        assertTrue(message.contains("Trello rejected"));
        assertFalse(message.contains("leaky-secret"), "the token in the URL must not reach the error message");
    }

    @Test
    void customHttpConnectionIsLabelledByItsName() {
        assertEquals("Acme API", verify("app_http", Map.of("name", "Acme API", "headerName", "Authorization", "headerValue", "Bearer x")));
    }

    // ---------- ProviderHttp failure handling ----------

    @Test
    void serverErrorsNonJsonAndUnreachableHostsGiveClearMessages() {
        ProviderHttp http = new ProviderHttp(JsonMapper.builder().build());
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        String serverError = assertThrows(ConnectionVerificationException.class,
                () -> http.get("Acme", base + "/broken/user", Map.of())).getMessage();
        assertEquals("Acme returned HTTP 500 while checking the connection", serverError);

        String notJson = assertThrows(ConnectionVerificationException.class,
                () -> http.get("Acme", base + "/html/user", Map.of())).getMessage();
        assertEquals("Acme sent a response that wasn't JSON", notJson);

        String unreachable = assertThrows(ConnectionVerificationException.class,
                () -> http.get("Acme", "http://127.0.0.1:1/user?token=leaky-secret", Map.of())).getMessage();
        assertTrue(unreachable.startsWith("Couldn't reach Acme"), unreachable);
        assertFalse(unreachable.contains("leaky-secret"));
        assertFalse(unreachable.contains("127.0.0.1"), "no URL in the message");
    }

    @Test
    void stringWalksNestedMapsSafely() {
        Map<String, Object> json = Map.of("bot", Map.of("workspace_name", "Acme"), "n", 5);
        assertEquals("Acme", ProviderHttp.string(json, "bot", "workspace_name"));
        assertEquals("5", ProviderHttp.string(json, "n"));
        assertNull(ProviderHttp.string(json, "bot", "missing"));
        assertNull(ProviderHttp.string(json, "n", "deeper"));
    }
}
