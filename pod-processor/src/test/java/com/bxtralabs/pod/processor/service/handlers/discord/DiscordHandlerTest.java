package com.bxtralabs.pod.processor.service.handlers.discord;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.AccountRejectedException;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import com.bxtralabs.pod.processor.service.handlers.StepContext;
import com.bxtralabs.pod.processor.service.handlers.StepCredentials;
import com.bxtralabs.pod.processor.service.handlers.UncertainStepException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiscordHandlerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private DiscordHandler handler;
    // "METHOD path?query body" per call, in order.
    private final List<String> calls = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();
    // "METHOD path" -> "status:body"; anything else posts message 555.
    private final Map<String, String> answers = new HashMap<>();

    private static final StepCredentials BOT = new StepCredentials("con_1", "app_discord", "TOKEN", Map.of("token", "bot-1"));
    private static final String CHANNEL = "123456789012345678";
    private static final StepContext FIRST = new StepContext("str_abc", 1, 1_760_000_000_000L, false);
    private static final StepContext RETRY = new StepContext("str_abc", 2, 1_760_000_000_000L, true);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();
            calls.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query) + " " + body);
            auth.add(ex.getRequestHeaders().getFirst("Authorization") + " | " + ex.getRequestHeaders().getFirst("User-Agent"));
            String answer = answers.getOrDefault(ex.getRequestMethod() + " " + path,
                    "200:{\"id\":\"555\",\"channel_id\":\"" + CHANNEL + "\",\"timestamp\":\"2026-10-04T10:00:00+00:00\"}");
            respond(ex, Integer.parseInt(answer.substring(0, 3)), answer.substring(4));
        });
        server.start();
        handler = new DiscordHandler(json, "http://127.0.0.1:" + server.getAddress().getPort());
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

    private static GraphNode node() {
        return new GraphNode("a", "action", "Discord", "act_discord_post", "Send Channel Message",
                DiscordHandler.SEND_MESSAGE, Map.of(), null, "app_discord", "con_1", null);
    }

    private Map<String, Object> send(Map<String, Object> input, StepContext context) throws Exception {
        return handler.execute(node(), input, BOT, context);
    }

    private Map<?, ?> sentBody() {
        String call = calls.getLast();
        return json.readValue(call.substring(call.indexOf(' ', call.indexOf(' ') + 1) + 1), Map.class);
    }

    @Test
    void postsAsTheBotWithANonceDiscordWontPostTwice() throws Exception {
        Map<String, Object> out = send(Map.of("channelId", CHANNEL, "content", "Deployed @everyone"), FIRST);

        assertEquals(1, calls.size());
        assertTrue(calls.getFirst().startsWith("POST /channels/" + CHANNEL + "/messages "), calls.getFirst());
        assertTrue(auth.getFirst().startsWith("Bot bot-1 | DiscordBot ("), auth.getFirst());
        Map<?, ?> body = sentBody();
        assertEquals("Deployed @everyone", body.get("content"));
        assertEquals(DiscordHandler.nonce(FIRST), body.get("nonce"));
        assertEquals(true, body.get("enforce_nonce"));
        assertEquals(List.of("users", "roles"), ((Map<?, ?>) body.get("allowed_mentions")).get("parse"), "no @everyone pings");
        assertFalse(body.containsKey("message_reference"));
        assertEquals(Map.of("id", "555", "channelId", CHANNEL, "content", "Deployed @everyone",
                "timestamp", "2026-10-04T10:00:00+00:00"), out);
    }

    @Test
    void theNonceIsTheSameOnEveryAttemptAndFitsDiscordsLimit() throws Exception {
        String nonce = DiscordHandler.nonce(FIRST);
        assertEquals(25, nonce.length());
        assertEquals(nonce, DiscordHandler.nonce(RETRY));
        assertNotEquals(nonce, DiscordHandler.nonce(new StepContext("str_other", 1, 0, false)));
    }

    @Test
    void takesAChannelLinkAndRepliesToAMessage() throws Exception {
        send(Map.of("channelId", "https://discord.com/channels/111111111111111111/" + CHANNEL,
                "content", "hi", "replyTo", "999999999999999999"), FIRST);
        assertTrue(calls.getFirst().startsWith("POST /channels/" + CHANNEL + "/messages "));
        assertEquals(Map.of("message_id", "999999999999999999", "fail_if_not_exists", false), sentBody().get("message_reference"));
    }

    @Test
    void settingsDiscordWouldRefuseFailBeforeCallingIt() {
        PermanentStepException e = assertThrows(PermanentStepException.class,
                () -> send(Map.of("channelId", "#general", "content", "hi"), FIRST));
        assertTrue(e.getMessage().contains("isn't a Discord channel ID or link"), e.getMessage());
        e = assertThrows(PermanentStepException.class,
                () -> send(Map.of("channelId", CHANNEL, "content", "x".repeat(2001)), FIRST));
        assertEquals("The message is 2001 characters long; Discord allows at most 2000", e.getMessage());
        e = assertThrows(PermanentStepException.class,
                () -> send(Map.of("channelId", CHANNEL, "content", "hi", "replyTo", "latest"), FIRST));
        assertTrue(e.getMessage().contains("must be a message ID"), e.getMessage());
        assertThrows(PermanentStepException.class, () -> handler.execute(node(), Map.of("channelId", CHANNEL, "content", "hi"),
                null, FIRST), "no account");
        assertTrue(calls.isEmpty());
    }

    @Test
    void aRejectedTokenIsTheAccountsProblem() {
        answers.put("POST /channels/" + CHANNEL + "/messages", "401:{\"message\":\"401: Unauthorized\",\"code\":0}");
        assertThrows(AccountRejectedException.class, () -> send(Map.of("channelId", CHANNEL, "content", "hi"), FIRST));
    }

    @Test
    void missingPermissionsSayWhatToGrant() {
        answers.put("POST /channels/" + CHANNEL + "/messages", "403:{\"message\":\"Missing Permissions\",\"code\":50013}");
        PermanentStepException e = assertThrows(PermanentStepException.class,
                () -> send(Map.of("channelId", CHANNEL, "content", "hi"), FIRST));
        assertTrue(e.getMessage().contains("Send Messages permission"), e.getMessage());

        answers.put("POST /channels/" + CHANNEL + "/messages", "404:{\"message\":\"Unknown Channel\",\"code\":10003}");
        e = assertThrows(PermanentStepException.class, () -> send(Map.of("channelId", CHANNEL, "content", "hi"), FIRST));
        assertEquals("Discord couldn't find channel " + CHANNEL, e.getMessage());
    }

    @Test
    void invalidRequestsSayWhichFieldDiscordObjectedTo() {
        answers.put("POST /channels/" + CHANNEL + "/messages", "400:{\"message\":\"Invalid Form Body\",\"code\":50035,"
                + "\"errors\":{\"content\":{\"_errors\":[{\"code\":\"BASE_TYPE_MAX_LENGTH\",\"message\":\"Must be 2000 or fewer in length.\"}]}}}");
        PermanentStepException e = assertThrows(PermanentStepException.class,
                () -> send(Map.of("channelId", CHANNEL, "content", "hi"), FIRST));
        assertEquals("Discord refused the message: Invalid Form Body (content: Must be 2000 or fewer in length.)", e.getMessage());
    }

    @Test
    void rateLimitsAreRetriedAndServerErrorsMayHavePosted() {
        answers.put("POST /channels/" + CHANNEL + "/messages", "429:{\"message\":\"You are being rate limited.\",\"retry_after\":1.5}");
        IllegalStateException retry = assertThrows(IllegalStateException.class, () -> send(Map.of("channelId", CHANNEL, "content", "hi"), FIRST));
        assertTrue(retry.getMessage().contains("rate limit"));
        answers.put("POST /channels/" + CHANNEL + "/messages", "502:{}");
        assertThrows(UncertainStepException.class, () -> send(Map.of("channelId", CHANNEL, "content", "hi"), FIRST));
    }

    @Test
    void aRetryFindsTheMessageAnEarlierAttemptPosted() throws Exception {
        answers.put("GET /users/@me", "200:{\"id\":\"42\",\"username\":\"autom8r\"}");
        answers.put("GET /channels/" + CHANNEL + "/messages", "200:["
                + "{\"id\":\"700\",\"content\":\"hi\",\"author\":{\"id\":\"7\"}},"
                + "{\"id\":\"701\",\"content\":\"hi\",\"author\":{\"id\":\"42\"},\"timestamp\":\"2026-10-04T10:00:00+00:00\"}]");

        Map<String, Object> out = send(Map.of("channelId", CHANNEL, "content", "hi"), RETRY);

        assertEquals("701", out.get("id"), "the bot's own message, not someone else's with the same text");
        assertEquals(true, out.get("alreadyDone"));
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("POST")), calls.toString());
        long after = (RETRY.firstStartedAt() - 60_000 - 1420070400000L) << 22;
        assertTrue(calls.get(1).contains("after=" + after), calls.get(1));
    }

    @Test
    void aRetryPostsWhenNothingWasPosted() throws Exception {
        answers.put("GET /users/@me", "200:{\"id\":\"42\"}");
        answers.put("GET /channels/" + CHANNEL + "/messages", "200:[{\"id\":\"700\",\"content\":\"other\",\"author\":{\"id\":\"42\"}}]");
        assertEquals("555", send(Map.of("channelId", CHANNEL, "content", "hi"), RETRY).get("id"));
        assertTrue(calls.getLast().startsWith("POST "));
    }

    @Test
    void aRetryThatCantCheckStopsRatherThanPostTwice() {
        answers.put("GET /users/@me", "200:{\"id\":\"42\"}");
        answers.put("GET /channels/" + CHANNEL + "/messages", "403:{\"message\":\"Missing Access\",\"code\":50001}");
        PermanentStepException e = assertThrows(PermanentStepException.class,
                () -> send(Map.of("channelId", CHANNEL, "content", "hi"), RETRY));
        assertTrue(e.getMessage().contains("Read Message History"), e.getMessage());
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("POST")));
    }
}
