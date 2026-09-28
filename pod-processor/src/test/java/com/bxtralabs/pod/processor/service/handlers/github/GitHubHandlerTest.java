package com.bxtralabs.pod.processor.service.handlers.github;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import com.bxtralabs.pod.processor.service.handlers.StepCredentials;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GitHubHandlerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private GitHubHandler handler;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<Map<?, ?>> sent = new AtomicReference<>();

    private static final StepCredentials OAUTH = new StepCredentials("con_1", "app_github", "OAUTH", Map.of("access_token", "gho_abc"));
    private static final StepCredentials PAT = new StepCredentials("con_2", "app_github", "TOKEN", Map.of("token", "ghp_xyz"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/", ex -> {
            path.set(ex.getRequestURI().getPath());
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            sent.set(json.readValue(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), Map.class));
            String repo = ex.getRequestURI().getPath().split("/")[3];
            switch (repo) {
                case "gone" -> respond(ex, 404, "{\"message\":\"Not Found\"}", null);
                case "denied" -> respond(ex, 403, "{\"message\":\"Resource not accessible by integration\"}", null);
                case "limited" -> respond(ex, 403, "{\"message\":\"API rate limit exceeded\"}", "0");
                case "flaky" -> respond(ex, 502, "{\"message\":\"Server Error\"}", null);
                case "revoked" -> respond(ex, 401, "{\"message\":\"Bad credentials\"}", null);
                case "picky" -> respond(ex, 422, "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"Label\",\"field\":\"name\",\"code\":\"invalid\"}]}", null);
                default -> respond(ex, 201, ex.getRequestURI().getPath().endsWith("/comments")
                        ? "{\"id\":55,\"html_url\":\"https://github.com/o/r/issues/7#issuecomment-55\"}"
                        : "{\"id\":900,\"number\":7,\"title\":\"Broken\",\"state\":\"open\",\"html_url\":\"https://github.com/o/r/issues/7\"}", null);
            }
        });
        server.start();
        handler = new GitHubHandler(json, "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body, String remaining) throws IOException {
        if (remaining != null) ex.getResponseHeaders().add("x-ratelimit-remaining", remaining);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static GraphNode node(String type) {
        return new GraphNode("a", "action", "GitHub", "item", "Step", type, Map.of(), null, "app_github", "con_1", null);
    }

    private Map<String, Object> issue(String repo, Object labels, StepCredentials creds) throws Exception {
        return handler.execute(node(GitHubHandler.CREATE_ISSUE),
                labels == null ? Map.of("repository", repo, "title", "Broken", "body", "It broke")
                        : Map.of("repository", repo, "title", "Broken", "labels", labels), creds);
    }

    @Test
    void createsAnIssueWithTheAccountsToken() throws Exception {
        Map<String, Object> out = issue("octo/app", "bug, triage, bug", OAUTH);
        assertEquals("/repos/octo/app/issues", path.get());
        assertEquals("Bearer gho_abc", auth.get());
        assertEquals(Map.of("title", "Broken", "labels", List.of("bug", "triage")), sent.get());
        assertEquals(7, out.get("number"));
        assertEquals("https://github.com/o/r/issues/7", out.get("url"));
    }

    @Test
    void aTokenConnectionUsesItsPersonalAccessToken() throws Exception {
        issue("octo/app", null, PAT);
        assertEquals("Bearer ghp_xyz", auth.get());
        assertEquals("It broke", sent.get().get("body"));
    }

    @Test
    void createsACommentOnAnIssueNumberGivenAsTextOrNumber() throws Exception {
        Map<String, Object> out = handler.execute(node(GitHubHandler.CREATE_COMMENT),
                Map.of("repository", "octo/app", "issueNumber", "7", "body", "Thanks!"), OAUTH);
        assertEquals("/repos/octo/app/issues/7/comments", path.get());
        assertEquals(Map.of("body", "Thanks!"), sent.get());
        assertEquals(55, out.get("id"));
        assertEquals(7L, out.get("issueNumber"));
    }

    @Test
    void problemsOnlyTheUserCanFixFailTheStepForGood() {
        assertTrue(assertThrows(PermanentStepException.class, () -> issue("octo/gone", null, OAUTH)).getMessage()
                .contains("couldn't find repository octo/gone"));
        assertTrue(assertThrows(PermanentStepException.class, () -> issue("octo/denied", null, OAUTH)).getMessage()
                .contains("Resource not accessible by integration"));
        assertTrue(assertThrows(PermanentStepException.class, () -> issue("octo/revoked", null, OAUTH)).getMessage()
                .contains("Reconnect it"));
        assertEquals("GitHub rejected the request: Validation Failed (Label name invalid)",
                assertThrows(PermanentStepException.class, () -> issue("octo/picky", null, OAUTH)).getMessage());
        assertThrows(PermanentStepException.class, () -> issue("not a repo", null, OAUTH));
        assertThrows(PermanentStepException.class, () -> issue("octo/app", null, null), "no account");
        assertThrows(PermanentStepException.class, () -> handler.execute(node(GitHubHandler.CREATE_COMMENT),
                Map.of("repository", "octo/app", "issueNumber", "seven", "body", "x"), OAUTH));
    }

    @Test
    void rateLimitsAndOutagesAreRetried() {
        // Not PermanentStepException: StepExecutor retries these.
        Exception limited = assertThrows(IllegalStateException.class, () -> issue("octo/limited", null, OAUTH));
        assertTrue(limited.getMessage().contains("rate limit"));
        // A 5xx on a create may have created it: retried, but flagged so the retry checks first.
        assertThrows(com.bxtralabs.pod.processor.service.handlers.UncertainStepException.class, () -> issue("octo/flaky", null, OAUTH));
        GitHubHandler unreachable = new GitHubHandler(json, "http://127.0.0.1:1");
        assertThrows(IOException.class, () -> unreachable.execute(node(GitHubHandler.CREATE_ISSUE),
                Map.of("repository", "octo/app", "title", "t"), OAUTH));
    }

    @Test
    void takesOverFromTheSimulatedHandler() {
        assertTrue(handler.supports(node("github.create_issue")));
        assertTrue(handler.supports(node("github.create_comment")));
        assertFalse(handler.supports(node("slack.post_message")));
    }

    @Test
    void aRejectedTokenIsReportedAsTheAccountsProblem() {
        assertThrows(com.bxtralabs.pod.processor.service.handlers.AccountRejectedException.class, () -> issue("octo/revoked", null, OAUTH));
        // but no access to one repository is not the account's fault
        assertFalse(assertThrows(PermanentStepException.class, () -> issue("octo/denied", null, OAUTH))
                instanceof com.bxtralabs.pod.processor.service.handlers.AccountRejectedException);
    }
}
