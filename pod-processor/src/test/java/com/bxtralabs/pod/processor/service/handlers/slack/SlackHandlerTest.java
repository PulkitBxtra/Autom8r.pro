package com.bxtralabs.pod.processor.service.handlers.slack;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SlackHandlerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private SlackHandler handler;
    // "METHOD path body-or-query" per call, in order.
    private final List<String> calls = new ArrayList<>();
    private volatile String answer = null;
    private volatile int status = 200;

    private static final StepCredentials BOT = new StepCredentials("con_1", "app_slack", "TOKEN", Map.of("token", "xoxb-1"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            calls.add(ex.getRequestMethod() + " " + path + " " + (body.isEmpty() ? ex.getRequestURI().getQuery() : body)
                    + " " + ex.getRequestHeaders().getFirst("Authorization"));
            String reply = answer != null ? answer : switch (path) {
                case "/users.lookupByEmail" -> "{\"ok\":true,\"user\":{\"id\":\"U0ADA\"}}";
                case "/conversations.open" -> "{\"ok\":true,\"channel\":{\"id\":\"D123\"}}";
                default -> "{\"ok\":true,\"channel\":\"C999\",\"ts\":\"1700000000.000100\"}";
            };
            respond(ex, status, reply);
        });
        server.start();
        handler = new SlackHandler(json, "http://127.0.0.1:" + server.getAddress().getPort());
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
        return new GraphNode("a", "action", "Slack", "item", "Step", type, Map.of(), null, "app_slack", "con_1", null);
    }

    private Map<String, Object> post(Map<String, Object> input) throws Exception {
        return handler.execute(node(SlackHandler.POST_MESSAGE), input, BOT);
    }

    @Test
    void postsToAChannelByNameWithTheBotToken() throws Exception {
        Map<String, Object> out = post(Map.of("channel", "#general", "text", "Deployed"));
        assertEquals(1, calls.size());
        assertTrue(calls.getFirst().startsWith("POST /chat.postMessage "), calls.getFirst());
        assertTrue(calls.getFirst().endsWith(" Bearer xoxb-1"));
        assertEquals(Map.of("channel", "general", "text", "Deployed"), json.readValue(calls.getFirst().split(" ", 3)[2].replace(" Bearer xoxb-1", ""), Map.class));
        assertEquals(Map.of("channel", "C999", "ts", "1700000000.000100", "text", "Deployed"), out);
    }

    @Test
    void repliesInAThreadWhenGivenOne() throws Exception {
        post(Map.of("channel", "C1", "text", "Update", "threadTs", "1700000000.000001"));
        assertTrue(calls.getFirst().contains("\"thread_ts\":\"1700000000.000001\""), calls.getFirst());
    }

    @Test
    void directMessagesAnEmailByLookingUpTheUserFirst() throws Exception {
        Map<String, Object> out = handler.execute(node(SlackHandler.SEND_DM), Map.of("user", "ada@example.com", "text", "Hi"), BOT);
        assertEquals(3, calls.size());
        assertTrue(calls.get(0).startsWith("GET /users.lookupByEmail email=ada@example.com"), calls.get(0));
        assertTrue(calls.get(1).contains("/conversations.open {\"users\":\"U0ADA\"}"), calls.get(1));
        assertTrue(calls.get(2).contains("/chat.postMessage ") && calls.get(2).contains("\"channel\":\"D123\""), calls.get(2));
        assertEquals("U0ADA", out.get("user"));
        assertEquals("D123", out.get("channel"));
    }

    @Test
    void slackErrorsTheUserMustFixFailForGood() {
        answer = "{\"ok\":false,\"error\":\"not_in_channel\"}";
        assertEquals("The Slack bot isn't in channel #ops. Invite it with /invite, then run again.",
                assertThrows(PermanentStepException.class, () -> post(Map.of("channel", "#ops", "text", "x"))).getMessage());
        answer = "{\"ok\":false,\"error\":\"missing_scope\",\"needed\":\"chat:write\"}";
        assertTrue(assertThrows(PermanentStepException.class, () -> post(Map.of("channel", "#ops", "text", "x"))).getMessage()
                .contains("(chat:write)"));
        answer = "{\"ok\":false,\"error\":\"token_revoked\"}";
        assertTrue(assertThrows(PermanentStepException.class, () -> post(Map.of("channel", "#ops", "text", "x"))).getMessage()
                .contains("Reconnect it"));
        assertThrows(PermanentStepException.class, () -> handler.execute(node(SlackHandler.POST_MESSAGE), Map.of("channel", "x", "text", "y"), null));
    }

    @Test
    void rateLimitsAndSlackOutagesAreRetried() {
        status = 429;
        answer = "{\"ok\":false,\"error\":\"ratelimited\"}";
        assertThrows(IllegalStateException.class, () -> post(Map.of("channel", "#a", "text", "x")));
        status = 200;
        answer = "{\"ok\":false,\"error\":\"internal_error\"}";
        assertThrows(IllegalStateException.class, () -> post(Map.of("channel", "#a", "text", "x")));
        status = 503;
        answer = "down";
        assertThrows(IllegalStateException.class, () -> post(Map.of("channel", "#a", "text", "x")));
    }
}
