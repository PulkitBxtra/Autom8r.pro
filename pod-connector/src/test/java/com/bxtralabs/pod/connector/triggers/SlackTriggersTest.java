package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.model.TriggerSubscription;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlackTriggersTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ConnectionRepository connections = mock(ConnectionRepository.class);
    private final Map<String, String> answers = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private HttpServer server;
    private SlackTriggers slack;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", ex -> {
            String method = ex.getRequestURI().getPath().substring("/api/".length());
            calls.add(method + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + " " + ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = answers.getOrDefault(method, "{\"ok\":false,\"error\":\"unknown_method\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        slack = slack("server-secret");
        answers.put("auth.test", "{\"ok\":true,\"team\":\"Acme\",\"team_id\":\"T1\",\"user\":\"autom8r\",\"user_id\":\"UBOT\",\"bot_id\":\"BBOT\"}");
        answers.put("conversations.list", "{\"ok\":true,\"channels\":[{\"id\":\"C0OTHER1\",\"name\":\"random\",\"is_member\":true}],"
                + "\"response_metadata\":{\"next_cursor\":\"page2\"}}");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SlackTriggers slack(String serverSecret) {
        return new SlackTriggers(json, connections, "http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                "https://hooks.example.com/", serverSecret);
    }

    private Connection connection(String authType, String oauthClientId) {
        Connection c = new Connection();
        c.setId("con_1");
        c.setUserId("usr_1");
        c.setAppId("app_slack");
        c.setAuthType(authType);
        c.setOauthClientId(oauthClientId);
        when(connections.findById("con_1")).thenReturn(Optional.of(c));
        return c;
    }

    private TriggerSubscription subscription(String triggerId, Map<String, Object> config) {
        TriggerSubscription s = new TriggerSubscription();
        s.setId("tsub_1");
        s.setAppId("app_slack");
        s.setTriggerId(triggerId);
        s.setConnectionId("con_1");
        s.setConfig(config);
        return s;
    }

    @Test
    void connectedWithSlackAChannelTriggerIsMatchedByWorkspaceAndChannel() throws Exception {
        connection(Connection.AUTH_OAUTH, null);
        // The channel is on the second page of the list.
        server.createContext("/api/conversations.list", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = (body.contains("cursor=page2")
                    ? "{\"ok\":true,\"channels\":[{\"id\":\"C0SUPPORT\",\"name\":\"support\",\"is_member\":true}]}"
                    : answers.get("conversations.list")).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        TriggerSubscription s = subscription(SlackTriggers.NEW_MESSAGE, Map.of("channel", "#support"));
        assertNull(slack.register(s, Map.of("access_token", "xoxb-1"), "unused", "unused"));
        assertEquals("T1", s.getRoutingKey());
        assertEquals("server", s.getMeta().get("via"));
        assertEquals("C0SUPPORT", s.getMeta().get("channelId"));
        assertEquals("support", s.getMeta().get("channelName"));
        assertNull(s.getMeta().get("eventsUrl"), "the server's app already sends its events to us");
        assertTrue(calls.getFirst().endsWith("Bearer xoxb-1"));
    }

    @Test
    void aBotTokenConnectionNeedsItsSigningSecretAndGetsItsOwnEventsAddress() throws Exception {
        connection(Connection.AUTH_TOKEN, null);
        TriggerSubscription s = subscription(SlackTriggers.NEW_MENTION, Map.of());
        TriggerSetupException missing = assertThrows(TriggerSetupException.class,
                () -> slack.register(s, Map.of("token", "xoxb-own"), "unused", "unused"));
        assertTrue(missing.getMessage().contains("signing secret"));

        slack.register(s, Map.of("token", "xoxb-own", "signingSecret", "own-secret"), "unused", "unused");
        assertEquals("own", s.getMeta().get("via"));
        assertEquals("https://hooks.example.com/hooks/slack/connections/con_1", s.getMeta().get("eventsUrl"));
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("conversations")), "a mention trigger has no channel");
    }

    @Test
    void setupProblemsSayWhatToDo() {
        connection(Connection.AUTH_OAUTH, "oac_1");
        assertTrue(assertThrows(TriggerSetupException.class, () -> slack.register(subscription(SlackTriggers.NEW_MENTION, Map.of()),
                Map.of("access_token", "xoxb-1"), "u", "s")).getMessage().contains("your own OAuth app"));

        connection(Connection.AUTH_OAUTH, null);
        assertTrue(assertThrows(TriggerSetupException.class, () -> slack("").register(subscription(SlackTriggers.NEW_MENTION, Map.of()),
                Map.of("access_token", "xoxb-1"), "u", "s")).getMessage().contains("SLACK_SIGNING_SECRET"));

        answers.put("conversations.info", "{\"ok\":true,\"channel\":{\"id\":\"C0SUPPORT\",\"name\":\"support\",\"is_member\":false}}");
        String notMember = assertThrows(TriggerSetupException.class, () -> slack.register(
                subscription(SlackTriggers.NEW_MESSAGE, Map.of("channel", "C0SUPPORT")), Map.of("access_token", "xoxb-1"), "u", "s")).getMessage();
        assertTrue(notMember.contains("/invite @autom8r") && notMember.contains("#support"), notMember);

        answers.put("conversations.list", "{\"ok\":true,\"channels\":[]}");
        assertTrue(assertThrows(TriggerSetupException.class, () -> slack.register(
                subscription(SlackTriggers.NEW_MESSAGE, Map.of("channel", "nope")), Map.of("access_token", "xoxb-1"), "u", "s"))
                .getMessage().contains("no public channel #nope"));

        answers.put("auth.test", "{\"ok\":false,\"error\":\"token_revoked\"}");
        assertTrue(assertThrows(TriggerSetupException.class, () -> slack.register(subscription(SlackTriggers.NEW_MENTION, Map.of()),
                Map.of("access_token", "xoxb-1"), "u", "s")).getMessage().contains("Reconnect"));
    }

    private TriggerSubscription registered(String triggerId) {
        TriggerSubscription s = subscription(triggerId, Map.of());
        s.setMeta(Map.of("via", "server", "team", "Acme", "botUserId", "UBOT", "botId", "BBOT",
                "channelId", "C0SUPPORT", "channelName", "support"));
        return s;
    }

    @Test
    void aMessageInTheChannelBecomesTheTriggersData() {
        Map<String, Object> event = Map.of("type", "message", "channel", "C0SUPPORT", "user", "U123", "text", "help please",
                "ts", "1727600000.000100", "files", List.of(Map.of("name", "log.txt")));
        Map<String, Object> body = slack.toTriggerBody(registered(SlackTriggers.NEW_MESSAGE), event).orElseThrow();
        assertEquals("help please", body.get("text"));
        assertEquals("U123", body.get("user"));
        assertEquals("support", body.get("channelName"));
        assertEquals("Acme", body.get("team"));
        assertEquals(List.of("log.txt"), body.get("files"));
    }

    @Test
    void otherChannelsEditsAndTheBotsOwnMessagesAreIgnored() {
        TriggerSubscription s = registered(SlackTriggers.NEW_MESSAGE);
        assertTrue(slack.toTriggerBody(s, Map.of("type", "message", "channel", "C0OTHER1", "user", "U1", "text", "x")).isEmpty());
        assertTrue(slack.toTriggerBody(s, Map.of("type", "message", "subtype", "message_changed", "channel", "C0SUPPORT")).isEmpty());
        assertTrue(slack.toTriggerBody(s, Map.of("type", "message", "channel", "C0SUPPORT", "bot_id", "BBOT", "text", "x")).isEmpty(),
                "a workflow posting to the channel it listens on doesn't start itself");
        assertTrue(slack.toTriggerBody(s, Map.of("type", "message", "subtype", "thread_broadcast", "channel", "C0SUPPORT", "user", "U1")).isPresent());
        assertTrue(slack.toTriggerBody(s, Map.of("type", "app_mention", "channel", "C0SUPPORT", "user", "U1")).isEmpty());
    }

    @Test
    void aMentionInAnyChannelStartsTheMentionTrigger() {
        TriggerSubscription s = subscription(SlackTriggers.NEW_MENTION, Map.of());
        s.setMeta(Map.of("via", "server", "team", "Acme", "botUserId", "UBOT", "botId", "BBOT"));
        Map<String, Object> body = slack.toTriggerBody(s, Map.of("type", "app_mention", "channel", "C0OTHER1", "user", "U1",
                "text", "<@UBOT> deploy", "ts", "1.2", "thread_ts", "1.1")).orElseThrow();
        assertEquals("<@UBOT> deploy", body.get("text"));
        assertEquals("1.1", body.get("threadTs"));
        assertFalse(body.containsKey("channelName"));
        assertTrue(slack.toTriggerBody(s, Map.of("type", "message", "channel", "C0OTHER1", "user", "U1")).isEmpty());
    }

    @Test
    void theSignatureCheckIsSlacksV0Scheme() {
        // Slack's documented example.
        String body = "token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V"
                + "&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text="
                + "&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2FT1DC2JH3J%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN"
                + "&trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c";
        String signature = "v0=a2114d57b48eac39b9ad189dd8316235a7b4a8d21a10bd27519666489c69b503";
        String secret = "8f742231b10e8888abcd99yyyzzz85a5";
        long at = 1531420618_000L;
        assertTrue(TriggerService.validSlackSignature(secret, "1531420618", body.getBytes(), signature, at));
        assertFalse(TriggerService.validSlackSignature(secret, "1531420618", (body + "x").getBytes(), signature, at));
        assertFalse(TriggerService.validSlackSignature(secret, "1531420618", body.getBytes(), signature, at + 301_000),
                "too old: a replayed request");
        assertFalse(TriggerService.validSlackSignature("", "1531420618", body.getBytes(), signature, at));
        assertFalse(TriggerService.validSlackSignature(secret, null, body.getBytes(), signature, at));
    }
}
