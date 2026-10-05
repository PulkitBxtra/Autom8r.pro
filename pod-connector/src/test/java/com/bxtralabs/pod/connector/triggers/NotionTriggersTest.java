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

class NotionTriggersTest {

    private static final String DB = "11111111111111111111111111111111";
    private static final String DB_DASHED = "11111111-1111-1111-1111-111111111111";

    private final JsonMapper json = JsonMapper.builder().build();
    private final ConnectionRepository connections = mock(ConnectionRepository.class);
    private HttpServer server;
    private String base;
    private final Map<String, String> pages = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String answer = path.equals("/users/me") ? "{\"id\":\"bot-a\",\"type\":\"bot\"}"
                    : path.equals("/databases/" + DB) ? "{\"id\":\"" + DB_DASHED + "\",\"title\":[{\"plain_text\":\"Tasks\"}]}"
                    : path.startsWith("/pages/") ? pages.get(path.substring(7)) : null;
            byte[] out = (answer == null ? "{\"object\":\"error\",\"code\":\"object_not_found\"}" : answer).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(answer == null ? 404 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private NotionTriggers notion(String secret) {
        return new NotionTriggers(json, connections, base, secret);
    }

    private TriggerSubscription subscription(String triggerId, String database, String authType, String oauthClientId) {
        Connection c = new Connection();
        c.setId("con_1");
        c.setAuthType(authType);
        c.setOauthClientId(oauthClientId);
        when(connections.findById("con_1")).thenReturn(Optional.of(c));
        TriggerSubscription s = new TriggerSubscription();
        s.setTriggerId(triggerId);
        s.setConnectionId("con_1");
        s.setConfig(new LinkedHashMap<>(Map.of("databaseId", database)));
        return s;
    }

    @Test
    void turningOnChecksTheDatabaseAndRemembersTheAccountsBot() throws Exception {
        TriggerSubscription s = subscription(NotionTriggers.NEW_PAGE, "https://www.notion.so/acme/Tasks-" + DB + "?v=1", Connection.AUTH_OAUTH, null);
        assertEquals(new AppTriggerRegistrar.Registration(null, null), notion("secret").register(s, Map.of("access_token", "ntn_1"), "unused", "unused"));
        assertEquals("bot-a", s.getRoutingKey());
        assertEquals("Tasks", s.getMeta().get("databaseTitle"));
    }

    @Test
    void whatCantWorkSaysWhy() {
        TriggerSubscription s = subscription(NotionTriggers.NEW_PAGE, DB, Connection.AUTH_OAUTH, null);
        assertTrue(assertThrows(TriggerSetupException.class, () -> notion("").register(s, Map.of("access_token", "t"), "u", "u"))
                .getMessage().contains("aren't set up on this server"));
        TriggerSubscription token = subscription(NotionTriggers.NEW_PAGE, DB, Connection.AUTH_TOKEN, null);
        assertTrue(assertThrows(TriggerSetupException.class, () -> notion("secret").register(token, Map.of("token", "t"), "u", "u"))
                .getMessage().contains("Connect with Notion"));
        TriggerSubscription own = subscription(NotionTriggers.NEW_PAGE, DB, Connection.AUTH_OAUTH, "oc_1");
        assertTrue(assertThrows(TriggerSetupException.class, () -> notion("secret").register(own, Map.of("access_token", "t"), "u", "u"))
                .getMessage().contains("Connect with Notion"), "their own Notion app's events don't come here");
        TriggerSubscription hidden = subscription(NotionTriggers.NEW_PAGE, "22222222222222222222222222222222", Connection.AUTH_OAUTH, null);
        assertTrue(assertThrows(TriggerSetupException.class, () -> notion("secret").register(hidden, Map.of("access_token", "t"), "u", "u"))
                .getMessage().contains("share it with the Autom8r connection"));
    }

    @Test
    void aPageInTheWatchedDatabaseBecomesTheTriggersDataWithPlainProperties() throws Exception {
        pages.put("p1", "{\"id\":\"p1\",\"url\":\"https://www.notion.so/p1\",\"created_time\":\"2026-10-05T10:00:00.000Z\","
                + "\"parent\":{\"type\":\"database_id\",\"database_id\":\"" + DB_DASHED + "\"},\"properties\":{"
                + "\"Task\":{\"type\":\"title\",\"title\":[{\"plain_text\":\"Ship \"},{\"plain_text\":\"it\"}]},"
                + "\"Status\":{\"type\":\"status\",\"status\":{\"name\":\"In progress\"}},"
                + "\"Tags\":{\"type\":\"multi_select\",\"multi_select\":[{\"name\":\"a\"},{\"name\":\"b\"}]},"
                + "\"Due\":{\"type\":\"date\",\"date\":{\"start\":\"2026-10-31\"}},"
                + "\"Points\":{\"type\":\"number\",\"number\":3}}}");
        pages.put("p2", "{\"id\":\"p2\",\"parent\":{\"type\":\"database_id\",\"database_id\":\"99999999-9999-9999-9999-999999999999\"}}");
        TriggerSubscription s = subscription(NotionTriggers.NEW_PAGE, DB, Connection.AUTH_OAUTH, null);
        Map<String, Object> created = Map.of("id", "e1", "type", "page.created", "entity", Map.of("id", "p1", "type", "page"));

        Map<String, Object> body = notion("secret").toTriggerBody(s, created, Map.of("access_token", "ntn_1")).orElseThrow();
        assertEquals("Ship it", body.get("title"));
        assertEquals(Map.of("Task", "Ship it", "Status", "In progress", "Tags", List.of("a", "b"), "Due", "2026-10-31", "Points", 3),
                body.get("properties"));
        assertEquals("https://www.notion.so/p1", body.get("url"));

        assertTrue(notion("secret").toTriggerBody(s, Map.of("type", "page.created", "entity", Map.of("id", "p2", "type", "page")),
                Map.of("access_token", "ntn_1")).isEmpty(), "a page in another database");
        assertTrue(notion("secret").toTriggerBody(s, Map.of("type", "page.properties_updated", "entity", Map.of("id", "p1", "type", "page")),
                Map.of("access_token", "ntn_1")).isEmpty(), "an update, for a New Page trigger");
        assertTrue(notion("secret").toTriggerBody(s, Map.of("type", "page.created", "entity", Map.of("id", "gone", "type", "page")),
                Map.of("access_token", "ntn_1")).isEmpty(), "a page it can't read");
    }

    @Test
    void updatesCountForTheUpdatedItemTrigger() {
        assertTrue(NotionTriggers.matches(NotionTriggers.UPDATED, "page.properties_updated"));
        assertTrue(NotionTriggers.matches(NotionTriggers.UPDATED, "page.content_updated"));
        assertFalse(NotionTriggers.matches(NotionTriggers.UPDATED, "page.created"));
        assertFalse(NotionTriggers.matches(NotionTriggers.NEW_PAGE, "page.deleted"));
    }

    @Test
    void onlyTheAccountsOwnBotIsIgnored() {
        Map<String, Object> byBot = Map.of("authors", List.of(Map.of("id", "bot-a", "type", "bot")));
        assertTrue(NotionTriggers.onlyBy("bot-a", byBot));
        assertFalse(NotionTriggers.onlyBy("bot-b", byBot), "another integration's change still counts");
        assertFalse(NotionTriggers.onlyBy("bot-a", Map.of("authors", List.of(Map.of("id", "bot-a", "type", "bot"), Map.of("id", "u1", "type", "person")))));
    }
}
