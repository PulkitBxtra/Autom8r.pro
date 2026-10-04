package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.connections.OAuthClientService;
import com.bxtralabs.pod.connector.connections.OAuthProviders;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TrelloTriggersTest {

    private static final String BOARD = "5f00000000000000000000b1";
    private static final String OTHER_BOARD = "5f00000000000000000000b2";
    private static final String LIST = "5f0000000000000000000001";
    private static final Map<String, String> SERVER_KEY = Map.of("apiKey", "server-key", "token", "tok");

    private final JsonMapper json = JsonMapper.builder().build();
    private final ConnectionRepository connections = mock(ConnectionRepository.class);
    private final OAuthClientService clients = mock(OAuthClientService.class);
    private final OAuthProviders providers = new OAuthProviders("https://github.com", "", "", "repo", "", "",
            "https://api.notion.com/v1", "", "", "https://slack.com", "", "", "", "",
            "https://auth.atlassian.com", "trello-client", "trello-client-secret", "http://localhost:8084");
    private HttpServer server;
    private TrelloTriggers trello;
    // "METHOD path body | Authorization" per call.
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            calls.add(ex.getRequestMethod() + " " + path + " " + body + " | " + ex.getRequestHeaders().getFirst("Authorization"));
            String answer = path.endsWith("/lists/" + LIST) ? "{\"id\":\"" + LIST + "\",\"name\":\"Done\",\"idBoard\":\"" + BOARD + "\"}"
                    : path.endsWith("/webhooks") ? "{\"id\":\"wh_1\"}" : "{}";
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        trello = new TrelloTriggers(json, connections, providers, clients, base + "/key", base + "/oauth", "server-key", "server-secret");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TriggerSubscription subscription(String triggerId, Map<String, Object> config, String authType, String oauthClientId) {
        Connection c = new Connection();
        c.setId("con_1");
        c.setAuthType(authType);
        c.setOauthClientId(oauthClientId);
        when(connections.findById("con_1")).thenReturn(Optional.of(c));
        TriggerSubscription s = new TriggerSubscription();
        s.setId("tsub_1");
        s.setWorkflowId("wfl_1");
        s.setTriggerId(triggerId);
        s.setConnectionId("con_1");
        s.setConfig(new LinkedHashMap<>(config));
        return s;
    }

    @Test
    void aNewCardTriggerWatchesTheBoardSignedWithTheServersSecretForItsOwnKey() throws Exception {
        TriggerSubscription s = subscription(TrelloTriggers.NEW_CARD, Map.of("boardId", BOARD), Connection.AUTH_TOKEN, null);

        AppTriggerRegistrar.Registration r = trello.register(s, SERVER_KEY, "https://hooks.example.com/hooks/trello/tsub_1", "ours");

        assertEquals(new AppTriggerRegistrar.Registration("wh_1", "server-secret"), r);
        assertTrue(calls.getLast().startsWith("POST /key/webhooks callbackURL=https%3A%2F%2Fhooks.example.com%2Fhooks%2Ftrello%2Ftsub_1&idModel="
                + BOARD + "&description="), calls.getLast());
        assertTrue(calls.getLast().endsWith("| OAuth oauth_consumer_key=\"server-key\", oauth_token=\"tok\""));
        assertEquals("https://hooks.example.com/hooks/trello/tsub_1", s.getMeta().get("callbackUrl"));
    }

    @Test
    void aMovedCardTriggerWatchesItsListsBoard() throws Exception {
        TriggerSubscription s = subscription(TrelloTriggers.CARD_MOVED, Map.of("listId", LIST), Connection.AUTH_TOKEN, null);
        trello.register(s, Map.of("apiKey", "their-key", "token", "tok", "apiSecret", "their-secret"), "https://h/x", "ours");
        assertTrue(calls.getFirst().startsWith("GET /key/lists/" + LIST), calls.getFirst());
        assertTrue(calls.getLast().contains("idModel=" + BOARD), calls.getLast());
        assertEquals("Done", s.getMeta().get("listName"));
    }

    @Test
    void aSignedInAccountUsesItsOAuthClientsSecretAndBearerToken() throws Exception {
        when(clients.credentialsFor(any(), eq(null))).thenReturn(new OAuthClientService.ClientCredentials("trello-client", "trello-client-secret"));
        TriggerSubscription s = subscription(TrelloTriggers.NEW_CARD, Map.of("boardId", BOARD), Connection.AUTH_OAUTH, null);
        AppTriggerRegistrar.Registration r = trello.register(s, Map.of("access_token", "at-1"), "https://h/x", "ours");
        assertEquals("trello-client-secret", r.secret());
        assertTrue(calls.getLast().startsWith("POST /oauth/webhooks ") && calls.getLast().endsWith("| Bearer at-1"), calls.getLast());
    }

    @Test
    void someoneElsesKeyWithoutItsSecretIsToldWhatToAdd() {
        TriggerSubscription s = subscription(TrelloTriggers.NEW_CARD, Map.of("boardId", BOARD), Connection.AUTH_TOKEN, null);
        TriggerSetupException e = assertThrows(TriggerSetupException.class,
                () -> trello.register(s, Map.of("apiKey", "their-key", "token", "tok"), "https://h/x", "ours"));
        assertTrue(e.getMessage().contains("add that API secret"), e.getMessage());
        assertTrue(calls.isEmpty());
    }

    @Test
    void aListOnAnotherBoardIsRefused() {
        TriggerSubscription s = subscription(TrelloTriggers.NEW_CARD, Map.of("boardId", OTHER_BOARD, "listId", LIST), Connection.AUTH_TOKEN, null);
        TriggerSetupException e = assertThrows(TriggerSetupException.class, () -> trello.register(s, SERVER_KEY, "https://h/x", "ours"));
        assertTrue(e.getMessage().contains("isn't on the chosen board"), e.getMessage());
    }

    private static Map<String, Object> action(String type, Map<String, Object> data) {
        Map<String, Object> d = new LinkedHashMap<>(data);
        d.put("card", Map.of("id", "c1", "name", "Ship it", "shortLink", "AbCd1234"));
        d.put("board", Map.of("id", BOARD, "name", "Roadmap"));
        return Map.of("action", Map.of("id", "a1", "type", type, "date", "2026-10-04T10:00:00.000Z",
                "memberCreator", Map.of("fullName", "Ada Lovelace"), "data", d));
    }

    @Test
    void newCardsInTheWatchedListStartRunsAndOtherActionsDont() {
        TriggerSubscription anyList = subscription(TrelloTriggers.NEW_CARD, Map.of("boardId", BOARD), Connection.AUTH_TOKEN, null);
        TriggerSubscription oneList = subscription(TrelloTriggers.NEW_CARD, Map.of("boardId", BOARD, "listId", LIST), Connection.AUTH_TOKEN, null);
        Map<String, Object> created = action("createCard", Map.of("list", Map.of("id", LIST, "name", "Done")));

        Map<String, Object> body = trello.toTriggerBody(anyList, created).orElseThrow();
        assertEquals("https://trello.com/c/AbCd1234", body.get("url"));
        assertEquals("Done", body.get("listName"));
        assertEquals("Ada Lovelace", body.get("by"));
        assertTrue(trello.toTriggerBody(oneList, created).isPresent());
        assertTrue(trello.toTriggerBody(oneList, action("createCard", Map.of("list", Map.of("id", "other")))).isEmpty());
        assertTrue(trello.toTriggerBody(anyList, action("commentCard", Map.of())).isEmpty());
    }

    @Test
    void onlyMovesIntoTheWatchedListCount() {
        TriggerSubscription s = subscription(TrelloTriggers.CARD_MOVED, Map.of("listId", LIST), Connection.AUTH_TOKEN, null);
        Map<String, Object> moved = action("updateCard", Map.of("listBefore", Map.of("id", "l0", "name", "To do"),
                "listAfter", Map.of("id", LIST, "name", "Done")));
        Map<String, Object> body = trello.toTriggerBody(s, moved).orElseThrow();
        assertEquals("To do", body.get("fromListName"));
        assertEquals(LIST, body.get("listId"));
        assertTrue(trello.toTriggerBody(s, action("updateCard", Map.of("listBefore", Map.of("id", LIST), "listAfter", Map.of("id", "l9")))).isEmpty(),
                "moved out of the list, not into it");
        assertTrue(trello.toTriggerBody(s, action("updateCard", Map.of("old", Map.of("name", "x")))).isEmpty(), "renamed, not moved");
    }

    @Test
    void trelloSignaturesCoverTheBodyAndTheCallbackUrl() throws Exception {
        byte[] body = "{\"action\":{}}".getBytes(StandardCharsets.UTF_8);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        mac.update(body);
        String header = Base64.getEncoder().encodeToString(mac.doFinal("https://h/hooks/trello/t1".getBytes(StandardCharsets.UTF_8)));

        assertTrue(TriggerService.validTrelloSignature("s3cret", body, "https://h/hooks/trello/t1", header));
        assertFalse(TriggerService.validTrelloSignature("s3cret", body, "https://other/hooks/trello/t1", header), "another URL");
        assertFalse(TriggerService.validTrelloSignature("guess", body, "https://h/hooks/trello/t1", header));
        assertFalse(TriggerService.validTrelloSignature("s3cret", body, "https://h/hooks/trello/t1", null));
    }
}
