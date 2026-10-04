package com.bxtralabs.pod.processor.service.handlers.trello;

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

class TrelloHandlerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private TrelloHandler handler;
    // "METHOD path?query body" per call, in order.
    private final List<String> calls = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();
    // "METHOD path" -> "status:body"; a POST /cards not listed creates CARD.
    private final Map<String, String> answers = new HashMap<>();

    private static final StepCredentials ACCOUNT = new StepCredentials("con_1", "app_trello", "TOKEN",
            Map.of("apiKey", "key-1", "token", "tok-1"));
    private static final String LIST = "5f0000000000000000000001";
    private static final String OTHER_LIST = "5f0000000000000000000002";
    private static final String BOARD = "5f00000000000000000000b1";
    // Created 2026-10-04 (0x68e0f000 seconds), well after the first attempt below.
    private static final String CARD_ID = "68e0f000000000000000c001";
    private static final String CARD = "{\"id\":\"" + CARD_ID + "\",\"name\":\"Ship it\",\"desc\":\"\",\"idList\":\"" + LIST
            + "\",\"idBoard\":\"" + BOARD + "\",\"shortUrl\":\"https://trello.com/c/AbCd1234\"}";
    private static final StepContext FIRST = new StepContext("str_abc", 1, 1_759_000_000_000L, false);
    private static final StepContext RETRY = new StepContext("str_abc", 2, 1_759_000_000_000L, true);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath().replaceFirst("^/key", "");
            String query = ex.getRequestURI().getRawQuery();
            calls.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query) + " " + body);
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            String answer = answers.getOrDefault(ex.getRequestMethod() + " " + path, "200:" + CARD);
            respond(ex, Integer.parseInt(answer.substring(0, 3)), answer.substring(4));
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        handler = new TrelloHandler(json, base + "/key", base + "/oauth");
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
        return new GraphNode("a", "action", "Trello", "item", "Step", type, Map.of(), null, "app_trello", "con_1", null);
    }

    private Map<String, Object> create(Map<String, Object> input, StepContext context) throws Exception {
        return handler.execute(node(TrelloHandler.CREATE_CARD), input, ACCOUNT, context);
    }

    private Map<String, Object> move(Map<String, Object> input) throws Exception {
        return handler.execute(node(TrelloHandler.MOVE_CARD), input, ACCOUNT, FIRST);
    }

    private Map<?, ?> body(String call) {
        return json.readValue(call.substring(call.indexOf(' ', call.indexOf(' ') + 1) + 1), Map.class);
    }

    @Test
    void createsACardWithTheKeyAndTokenInTheHeaderNotTheUrl() throws Exception {
        Map<String, Object> out = create(Map.of("listId", LIST, "name", "Ship it", "description", "Notes",
                "position", "top", "due", "2026-10-31"), FIRST);

        assertEquals(1, calls.size());
        assertTrue(calls.getFirst().startsWith("POST /cards?fields="), calls.getFirst());
        assertFalse(calls.getFirst().contains("tok-1") || calls.getFirst().contains("key-1"));
        assertEquals("OAuth oauth_consumer_key=\"key-1\", oauth_token=\"tok-1\"", auth.getFirst());
        assertEquals(Map.of("idList", LIST, "name", "Ship it", "desc", "Notes", "pos", "top", "due", "2026-10-31T00:00:00Z"),
                body(calls.getFirst()));
        assertEquals(Map.of("id", CARD_ID, "name", "Ship it", "url", "https://trello.com/c/AbCd1234", "listId", LIST,
                "boardId", BOARD), out);
    }

    @Test
    void aSignedInAccountUsesItsAccessTokenOnTrellosOauthAddress() throws Exception {
        StepCredentials signedIn = new StepCredentials("con_2", "app_trello", "OAUTH", Map.of("access_token", "at-1"));
        Map<String, Object> out = handler.execute(node(TrelloHandler.CREATE_CARD), Map.of("listId", LIST, "name", "Ship it"), signedIn, FIRST);
        assertTrue(calls.getFirst().startsWith("POST /oauth/cards?"), calls.getFirst());
        assertEquals("Bearer at-1", auth.getFirst());
        assertEquals(CARD_ID, out.get("id"));

        answers.put("POST /oauth/cards", "401:invalid token");
        AccountRejectedException e = assertThrows(AccountRejectedException.class, () -> handler.execute(node(TrelloHandler.CREATE_CARD),
                Map.of("listId", LIST, "name", "x"), signedIn, FIRST));
        assertTrue(e.getMessage().contains("sign-in"), e.getMessage());
    }

    @Test
    void positionDefaultsToTheBottomAndEmptyDescriptionsAreLeftOut() throws Exception {
        create(Map.of("listId", LIST, "name", "Ship it", "description", ""), FIRST);
        assertEquals(Map.of("idList", LIST, "name", "Ship it", "pos", "bottom"), body(calls.getFirst()));
    }

    @Test
    void settingsTrelloWouldRefuseFailBeforeCallingIt() {
        assertTrue(assertThrows(PermanentStepException.class, () -> create(Map.of("listId", "To do", "name", "x"), FIRST))
                .getMessage().contains("isn't a Trello list ID"));
        assertEquals("The card needs a name", assertThrows(PermanentStepException.class,
                () -> create(Map.of("listId", LIST, "name", " "), FIRST)).getMessage());
        assertTrue(assertThrows(PermanentStepException.class, () -> create(Map.of("listId", LIST, "name", "x", "due", "next week"), FIRST))
                .getMessage().startsWith("Due date must be"));
        assertTrue(assertThrows(PermanentStepException.class, () -> create(Map.of("listId", LIST, "name", "x", "position", "middle"), FIRST))
                .getMessage().startsWith("Position must be top or bottom"));
        assertTrue(assertThrows(PermanentStepException.class, () -> move(Map.of("cardId", "card 7", "listId", LIST)))
                .getMessage().contains("isn't a Trello card ID or link"));
        assertThrows(PermanentStepException.class, () -> handler.execute(node(TrelloHandler.CREATE_CARD),
                Map.of("listId", LIST, "name", "x"), new StepCredentials("c", "app_trello", "TOKEN", Map.of("token", "t")), FIRST),
                "a key is needed too");
        assertTrue(calls.isEmpty());
    }

    @Test
    void movesACardFromALinkToAListOnAnyBoard() throws Exception {
        answers.put("GET /lists/" + OTHER_LIST, "200:{\"id\":\"" + OTHER_LIST + "\",\"idBoard\":\"" + BOARD + "\"}");

        Map<String, Object> out = move(Map.of("cardId", "https://trello.com/c/AbCd1234/12-ship-it", "listId", OTHER_LIST, "position", "top"));

        assertEquals(2, calls.size());
        assertTrue(calls.get(1).startsWith("PUT /cards/AbCd1234?idList=" + OTHER_LIST + "&idBoard=" + BOARD + "&pos=top&fields="), calls.get(1));
        assertEquals(CARD_ID, out.get("id"));
    }

    @Test
    void anInvalidTokenIsTheAccountsProblemButNoAccessToABoardIsNot() {
        answers.put("POST /cards", "401:invalid token");
        assertThrows(AccountRejectedException.class, () -> create(Map.of("listId", LIST, "name", "x"), FIRST));

        answers.put("POST /cards", "401:unauthorized permission requested");
        PermanentStepException e = assertThrows(PermanentStepException.class, () -> create(Map.of("listId", LIST, "name", "x"), FIRST));
        assertFalse(e instanceof AccountRejectedException);
        assertTrue(e.getMessage().contains("can't reach list " + LIST), e.getMessage());
    }

    @Test
    void unknownCardsAndListsSaySo() {
        answers.put("GET /lists/" + LIST, "200:{\"idBoard\":\"" + BOARD + "\"}");
        answers.put("PUT /cards/AbCd1234", "404:The requested resource was not found.");
        assertEquals("Trello couldn't find card AbCd1234", assertThrows(PermanentStepException.class,
                () -> move(Map.of("cardId", "AbCd1234", "listId", LIST))).getMessage());
        answers.put("POST /cards", "400:{\"message\":\"invalid value for idList\"}");
        assertTrue(assertThrows(PermanentStepException.class, () -> create(Map.of("listId", LIST, "name", "x"), FIRST))
                .getMessage().endsWith("invalid value for idList"));
    }

    @Test
    void rateLimitsAreRetriedAndServerErrorsMayHaveCreatedTheCard() {
        answers.put("POST /cards", "429:{\"message\":\"API_TOKEN_LIMIT_EXCEEDED\"}");
        assertTrue(assertThrows(IllegalStateException.class, () -> create(Map.of("listId", LIST, "name", "x"), FIRST))
                .getMessage().contains("rate limit"));
        answers.put("POST /cards", "503:Service Unavailable");
        assertThrows(UncertainStepException.class, () -> create(Map.of("listId", LIST, "name", "x"), FIRST));
    }

    @Test
    void aRetryFindsTheCardAnEarlierAttemptCreated() throws Exception {
        // Same name in the list, but created long before the first attempt: someone else's card.
        String old = "{\"id\":\"50000000000000000000c000\",\"name\":\"Ship it\",\"desc\":\"Notes\",\"idList\":\"" + LIST + "\"}";
        String ours = "{\"id\":\"" + CARD_ID + "\",\"name\":\"Ship it\",\"desc\":\"Notes\",\"idList\":\"" + LIST
                + "\",\"idBoard\":\"" + BOARD + "\",\"shortUrl\":\"https://trello.com/c/AbCd1234\"}";
        answers.put("GET /lists/" + LIST + "/cards", "200:[" + old + "," + ours + "]");

        Map<String, Object> out = create(Map.of("listId", LIST, "name", "Ship it", "description", "Notes"), RETRY);

        assertEquals(CARD_ID, out.get("id"));
        assertEquals(true, out.get("alreadyDone"));
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("POST")), calls.toString());
    }

    @Test
    void aRetryCreatesTheCardWhenNoneWasCreated() throws Exception {
        String old = "{\"id\":\"50000000000000000000c000\",\"name\":\"Ship it\",\"desc\":\"\",\"idList\":\"" + LIST + "\"}";
        answers.put("GET /lists/" + LIST + "/cards", "200:[" + old + "]");
        create(Map.of("listId", LIST, "name", "Ship it"), RETRY);
        assertTrue(calls.getLast().startsWith("POST /cards"), calls.toString());
    }

    @Test
    void theCreationTimeIsReadFromTheId() {
        assertEquals(0x68e0f000L, TrelloHandler.createdAt(CARD_ID));
        assertEquals(0, TrelloHandler.createdAt("AbCd1234"));
    }
}
