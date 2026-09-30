package com.bxtralabs.pod.processor.service.handlers;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.github.GitHubHandler;
import com.bxtralabs.pod.processor.service.handlers.notion.NotionHandler;
import com.bxtralabs.pod.processor.service.handlers.slack.SlackHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

// C5: a retry after an uncertain attempt finds the earlier attempt's work instead of repeating it.
class NotTwiceTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private String base;
    // "METHOD path?query" of every request, and the bodies/headers sent.
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    // path -> canned answer for GETs and lookups
    private final Map<String, String> answers = new ConcurrentHashMap<>();

    private static final StepContext FIRST = new StepContext("stp_1", 1, 1_700_000_000_000L, false);
    private static final StepContext RETRY = new StepContext("stp_1", 2, 1_700_000_000_000L, true);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            requests.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), v.getFirst()));
            if (path.equals("/slow")) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
            }
            String answer = answers.getOrDefault(path, defaultAnswer(ex.getRequestMethod(), path));
            respond(ex, answer.startsWith("!") ? Integer.parseInt(answer.substring(1, 4)) : 200,
                    answer.startsWith("!") ? answer.substring(4) : answer);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String defaultAnswer(String method, String path) {
        if (path.endsWith("/issues") && method.equals("POST")) return "{\"id\":1,\"number\":9,\"html_url\":\"https://github.com/o/r/issues/9\"}";
        if (path.endsWith("/comments") && method.equals("POST")) return "{\"id\":5,\"html_url\":\"https://github.com/o/r/issues/9#c5\"}";
        if (method.equals("GET") && (path.endsWith("/issues") || path.endsWith("/comments"))) return "[]";
        if (path.equals("/chat.postMessage")) return "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"1.2\"}";
        if (path.equals("/conversations.history")) return "{\"ok\":true,\"messages\":[]}";
        if (path.equals("/conversations.list")) return "{\"ok\":true,\"channels\":[{\"id\":\"C0DEPLOYS\",\"name\":\"deploys\"}]}";
        if (path.startsWith("/databases/") && path.endsWith("/query")) return "{\"results\":[]}";
        if (path.startsWith("/databases/")) return "{\"id\":\"d\",\"properties\":{\"Task\":{\"type\":\"title\"}}}";
        if (path.equals("/pages")) return "{\"id\":\"new-page\",\"url\":\"https://www.notion.so/new\"}";
        return "{}";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static GraphNode node(String type) {
        return new GraphNode("a", "action", "App", "item", "Step", type, Map.of(), null, "app", "con_1", null);
    }

    private long posts(String path) {
        return requests.stream().filter(r -> r.startsWith("POST " + path)).count();
    }

    // ---------- GitHub ----------

    private final StepCredentials github = new StepCredentials("con_1", "app_github", "OAUTH", Map.of("access_token", "gho_1"));

    @Test
    void githubIssuesCarryAHiddenMarkerOfTheirStep() throws Exception {
        new GitHubHandler(json, base).execute(node(GitHubHandler.CREATE_ISSUE),
                Map.of("repository", "o/r", "title", "Broken", "body", "Details"), github, FIRST);
        assertEquals("Details\n\n<!-- autom8r-step:stp_1 -->", json.readValue(bodies.getLast(), Map.class).get("body"));
        assertEquals(1, requests.size(), "a first attempt doesn't look for anything");
    }

    @Test
    void aGithubRetryReturnsTheIssueAnEarlierAttemptCreated() throws Exception {
        answers.put("/repos/o/r/issues", "[{\"id\":3,\"number\":8,\"body\":\"old\"},{\"id\":4,\"number\":7,"
                + "\"html_url\":\"https://github.com/o/r/issues/7\",\"body\":\"Details\\n\\n<!-- autom8r-step:stp_1 -->\"}]");
        Map<String, Object> out = new GitHubHandler(json, base).execute(node(GitHubHandler.CREATE_ISSUE),
                Map.of("repository", "o/r", "title", "Broken"), github, RETRY);
        assertEquals(7, out.get("number"));
        assertEquals(true, out.get("alreadyDone"));
        assertEquals(0, posts("/repos/o/r/issues"), "nothing created again");
        assertTrue(requests.getFirst().contains("since=2023-11-14T22:12:20Z"), "looks from a minute before the first attempt");
    }

    @Test
    void aGithubRetryCreatesTheIssueIfTheEarlierAttemptDidnt() throws Exception {
        answers.put("/repos/o/r/issues", "[]"); // GETs only; the POST below uses the default
        answers.remove("/repos/o/r/issues");
        Map<String, Object> out = new GitHubHandler(json, base).execute(node(GitHubHandler.CREATE_ISSUE),
                Map.of("repository", "o/r", "title", "Broken"), github, RETRY);
        assertEquals(9, out.get("number"));
        assertNull(out.get("alreadyDone"));
        assertEquals(1, posts("/repos/o/r/issues"));
    }

    @Test
    void aGithubCommentRetryFindsItsEarlierComment() throws Exception {
        answers.put("/repos/o/r/issues/7/comments", "[{\"id\":55,\"html_url\":\"u\",\"body\":\"Thanks <!-- autom8r-step:stp_1 -->\"}]");
        Map<String, Object> out = new GitHubHandler(json, base).execute(node(GitHubHandler.CREATE_COMMENT),
                Map.of("repository", "o/r", "issueNumber", 7, "body", "Thanks"), github, RETRY);
        assertEquals(55, out.get("id"));
        assertEquals(0, posts("/repos/o/r/issues/7/comments"));
    }

    // ---------- Slack ----------

    private final StepCredentials slack = new StepCredentials("con_1", "app_slack", "TOKEN", Map.of("token", "xoxb-1"));

    @Test
    void slackMessagesCarryTheirStepInMetadata() throws Exception {
        new SlackHandler(json, base).execute(node(SlackHandler.POST_MESSAGE), Map.of("channel", "#deploys", "text", "Live"), slack, FIRST);
        Map<?, ?> sent = json.readValue(bodies.getLast(), Map.class);
        assertEquals(Map.of("event_type", "autom8r_step", "event_payload", Map.of("step", "autom8r-step:stp_1")), sent.get("metadata"));
    }

    @Test
    void aSlackRetryFindsItsEarlierMessageInTheChannelsHistory() throws Exception {
        answers.put("/conversations.history", "{\"ok\":true,\"messages\":[{\"ts\":\"1.1\"},{\"ts\":\"1.5\",\"metadata\":"
                + "{\"event_type\":\"autom8r_step\",\"event_payload\":{\"step\":\"autom8r-step:stp_1\"}}}]}");
        Map<String, Object> out = new SlackHandler(json, base).execute(node(SlackHandler.POST_MESSAGE),
                Map.of("channel", "#deploys", "text", "Live"), slack, RETRY);
        assertEquals(Map.of("channel", "C0DEPLOYS", "ts", "1.5", "text", "Live", "alreadyDone", true), out);
        assertEquals(0, posts("/chat.postMessage"));
        assertTrue(requests.stream().anyMatch(r -> r.startsWith("GET /conversations.history?channel=C0DEPLOYS&oldest=1699999940")), requests.toString());
    }

    @Test
    void ifSlackWontLetItCheckItStopsRatherThanRiskADuplicate() {
        answers.put("/conversations.history", "{\"ok\":false,\"error\":\"missing_scope\",\"needed\":\"channels:history\"}");
        PermanentStepException e = assertThrows(PermanentStepException.class, () -> new SlackHandler(json, base)
                .execute(node(SlackHandler.POST_MESSAGE), Map.of("channel", "C0DEPLOYS", "text", "Live"), slack, RETRY));
        assertTrue(e.getMessage().startsWith("An earlier try may already have posted this message"), e.getMessage());
        assertEquals(0, posts("/chat.postMessage"));
    }

    // ---------- Notion ----------

    private final StepCredentials notion = new StepCredentials("con_1", "app_notion", "TOKEN", Map.of("token", "ntn_1"));
    private static final String DB = "11111111-1111-1111-1111-111111111111";
    private static final String PAGE = "22222222-2222-2222-2222-222222222222";

    @Test
    void aNotionRetryFindsTheRowAnEarlierAttemptAdded() throws Exception {
        answers.put("/databases/" + DB + "/query", "{\"results\":[{\"id\":\"row-1\",\"url\":\"https://www.notion.so/row1\"}]}");
        Map<String, Object> out = new NotionHandler(json, base).execute(node(NotionHandler.CREATE_PAGE),
                Map.of("parentId", DB, "title", "Ship it"), notion, RETRY);
        assertEquals(Map.of("id", "row-1", "url", "https://www.notion.so/row1", "parent", "database", "alreadyDone", true), out);
        assertEquals(0, posts("/pages"));
        Map<?, ?> filter = (Map<?, ?>) json.readValue(bodies.get(1), Map.class).get("filter");
        assertTrue(filter.toString().contains("equals=Ship it") && filter.toString().contains("on_or_after=2023-11-14T22:12:20Z"), filter.toString());
    }

    @Test
    void aNotionRetryFindsASubpageByTitleAndTime() throws Exception {
        answers.put("/databases/" + PAGE, "!404{\"object\":\"error\",\"code\":\"object_not_found\"}");
        answers.put("/blocks/" + PAGE + "/children", "{\"results\":[{\"type\":\"child_page\",\"id\":\"old\",\"created_time\":\"2023-01-01T00:00:00.000Z\",\"child_page\":{\"title\":\"Notes\"}},"
                + "{\"type\":\"child_page\",\"id\":\"sub-1\",\"created_time\":\"2023-11-14T22:13:00.000Z\",\"child_page\":{\"title\":\"Notes\"}}],\"has_more\":false}");
        Map<String, Object> out = new NotionHandler(json, base).execute(node(NotionHandler.CREATE_PAGE),
                Map.of("parentId", PAGE, "title", "Notes"), notion, RETRY);
        assertEquals("sub-1", out.get("id"), "the older page with the same title isn't this step's");
        assertEquals(0, posts("/pages"));
    }

    @Test
    void aNotionRetryCreatesThePageWhenThereIsNone() throws Exception {
        Map<String, Object> out = new NotionHandler(json, base).execute(node(NotionHandler.CREATE_PAGE),
                Map.of("parentId", DB, "title", "Ship it"), notion, RETRY);
        assertEquals("new-page", out.get("id"));
        assertEquals(1, posts("/pages"));
    }

    // ---------- HTTP ----------

    @Test
    void httpRequestsThatChangeSomethingCarryAnIdempotencyKey() throws Exception {
        HttpRequestHandler http = new HttpRequestHandler(json, true);
        http.execute(node("http_request"), Map.of("url", base + "/x", "method", "POST", "body", Map.of("a", 1)), null, RETRY);
        assertEquals("stp_1", lastHeaders.get("idempotency-key"));
        lastHeaders.clear();
        http.execute(node("http_request"), Map.of("url", base + "/x", "method", "GET"), null, RETRY);
        assertNull(lastHeaders.get("idempotency-key"), "GET doesn't need one");
        http.execute(node("http_request"), Map.of("url", base + "/x", "method", "PUT", "headers", Map.of("idempotency-key", "mine")), null, RETRY);
        assertEquals("mine", lastHeaders.get("idempotency-key"), "the step's own key wins");
    }

    // ---------- sending ----------

    @Test
    void aRequestThatNeverLeftIsSafeToRetryButOneWithNoAnswerIsUncertain() {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest refused = HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/x")).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThrows(IOException.class, () -> AppCalls.send(client, refused, "App", true));

        HttpRequest slow = HttpRequest.newBuilder(URI.create(base + "/slow")).timeout(Duration.ofMillis(300))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThrows(UncertainStepException.class, () -> AppCalls.send(client, slow, "App", true), "sent, no answer: may have happened");
        assertThrows(IOException.class, () -> AppCalls.send(client, slow, "App", false), "a read-only call is just retried");
    }
}
