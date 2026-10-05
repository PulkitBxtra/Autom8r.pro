package com.bxtralabs.pod.processor.service.handlers.gmail;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.AccountRejectedException;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import com.bxtralabs.pod.processor.service.handlers.StepContext;
import com.bxtralabs.pod.processor.service.handlers.StepCredentials;
import com.bxtralabs.pod.processor.service.handlers.UncertainStepException;
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

class GmailHandlerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private GmailHandler handler;
    // "METHOD path?query body | Authorization" per call.
    private final List<String> calls = new ArrayList<>();
    // "METHOD path" -> "status:body"
    private final Map<String, String> answers = new HashMap<>();

    private static final StepCredentials ACCOUNT = new StepCredentials("con_1", "app_gmail", "OAUTH", Map.of("access_token", "ya29.1"));
    private static final StepContext FIRST = new StepContext("str_abc", 1, 0, false);
    private static final StepContext RETRY = new StepContext("str_abc", 2, 0, true);

    @BeforeEach
    void start() throws IOException {
        answers.put("POST /users/me/messages/send", "200:{\"id\":\"m1\",\"threadId\":\"t1\"}");
        answers.put("POST /users/me/drafts", "200:{\"id\":\"d1\",\"message\":{\"id\":\"m2\",\"threadId\":\"t2\"}}");
        answers.put("GET /users/me/messages", "200:{\"resultSizeEstimate\":0}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();
            calls.add(key + (query == null ? "" : "?" + query) + " " + body + " | " + ex.getRequestHeaders().getFirst("Authorization"));
            String answer = answers.getOrDefault(key, "404:{\"error\":{\"code\":404,\"message\":\"Not Found\"}}");
            byte[] out = answer.substring(4).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(Integer.parseInt(answer.substring(0, 3)), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        handler = new GmailHandler(json, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static GraphNode node(String type) {
        return new GraphNode("a", "action", "Gmail", "item", "Step", type, Map.of(), null, "app_gmail", "con_1", null);
    }

    private Map<String, Object> send(Map<String, Object> input, StepContext context) throws Exception {
        return handler.execute(node(GmailHandler.SEND), input, ACCOUNT, context);
    }

    // The MIME message the last call carried.
    private String sentMime() {
        String call = calls.getLast();
        Map<?, ?> body = json.readValue(call.substring(call.indexOf('{'), call.lastIndexOf(" | ")), Map.class);
        Object raw = body.containsKey("raw") ? body.get("raw") : ((Map<?, ?>) body.get("message")).get("raw");
        return new String(Base64.getUrlDecoder().decode(String.valueOf(raw)), StandardCharsets.UTF_8);
    }

    private static String bodyOf(String mime) {
        String encoded = mime.substring(mime.indexOf("\r\n\r\n") + 4).replace("\r\n", "");
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    @Test
    void sendsFromTheAccountWithAMessageIdFromTheStepRun() throws Exception {
        Map<String, Object> out = send(Map.of("to", "ada@example.com, Grace <grace@example.com>", "cc", "ops@example.com",
                "subject", "Order 1042 shipped", "body", "It's on its way."), FIRST);

        assertTrue(calls.getFirst().startsWith("POST /users/me/messages/send "), calls.getFirst());
        assertTrue(calls.getFirst().endsWith("| Bearer ya29.1"));
        String mime = sentMime();
        assertTrue(mime.startsWith("To: ada@example.com, Grace <grace@example.com>\r\nCc: ops@example.com\r\nSubject: Order 1042 shipped\r\n"
                + "Message-ID: <autom8r-step-str_abc@autom8r.pro>\r\n"), mime);
        assertTrue(mime.contains("Content-Type: text/plain; charset=UTF-8"));
        assertEquals("It's on its way.", bodyOf(mime));
        assertEquals(Map.of("id", "m1", "threadId", "t1", "to", "ada@example.com, Grace <grace@example.com>", "subject", "Order 1042 shipped"), out);
    }

    @Test
    void nonAsciiSubjectsAndHtmlBodiesSurvive() throws Exception {
        send(Map.of("to", "ada@example.com", "subject", "Bestellung ✓ versandt", "body", "<p>Grüße</p>", "bodyType", "html"), FIRST);
        String mime = sentMime();
        assertTrue(mime.contains("Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString("Bestellung ✓ versandt".getBytes(StandardCharsets.UTF_8)) + "?="), mime);
        assertTrue(mime.contains("Content-Type: text/html; charset=UTF-8"));
        assertEquals("<p>Grüße</p>", bodyOf(mime));
    }

    @Test
    void draftsAreSavedNotSent() throws Exception {
        Map<String, Object> out = handler.execute(node(GmailHandler.DRAFT), Map.of("subject", "Idea", "body", "Later"), ACCOUNT, FIRST);
        assertTrue(calls.getFirst().startsWith("POST /users/me/drafts "), calls.getFirst());
        assertFalse(sentMime().contains("To:"), "a draft may have no recipient yet");
        assertEquals("d1", out.get("draftId"));
        assertEquals("m2", out.get("id"));
    }

    @Test
    void injectedHeadersAndBadAddressesFailBeforeCallingGmail() {
        assertTrue(assertThrows(PermanentStepException.class, () -> send(Map.of("to", "ada@example.com",
                "subject", "Hi\r\nBcc: everyone@example.com", "body", "x"), FIRST)).getMessage().contains("can't contain line breaks"));
        assertTrue(assertThrows(PermanentStepException.class, () -> send(Map.of("to", "ada@example.com\nBcc: x@example.com",
                "subject", "Hi", "body", "x"), FIRST)).getMessage().contains("can't contain line breaks"));
        assertTrue(assertThrows(PermanentStepException.class, () -> send(Map.of("to", "ada at example", "subject", "Hi", "body", "x"), FIRST))
                .getMessage().contains("isn't an email address"));
        assertTrue(assertThrows(PermanentStepException.class, () -> send(Map.of("to", " ", "subject", "Hi", "body", "x"), FIRST))
                .getMessage().contains("at least one address"));
        assertThrows(PermanentStepException.class, () -> handler.execute(node(GmailHandler.SEND),
                Map.of("to", "a@example.com", "subject", "Hi", "body", "x"), null, FIRST), "no account");
        assertTrue(calls.isEmpty());
    }

    @Test
    void aRetryFindsTheEmailAnEarlierAttemptSent() throws Exception {
        answers.put("GET /users/me/messages", "200:{\"messages\":[{\"id\":\"m9\",\"threadId\":\"t9\"}]}");
        Map<String, Object> out = send(Map.of("to", "ada@example.com", "subject", "Hi", "body", "x"), RETRY);
        assertEquals("m9", out.get("id"));
        assertEquals(true, out.get("alreadyDone"));
        assertTrue(calls.getFirst().contains("q=rfc822msgid:<autom8r-step-str_abc@autom8r.pro>"), calls.getFirst());
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("POST")));
    }

    @Test
    void aRetryThatFindsNothingSends() throws Exception {
        send(Map.of("to", "ada@example.com", "subject", "Hi", "body", "x"), RETRY);
        assertTrue(calls.getLast().startsWith("POST /users/me/messages/send"), calls.toString());
    }

    @Test
    void aRetryThatCantCheckStopsRatherThanSendTwice() {
        answers.put("GET /users/me/messages", "403:{\"error\":{\"code\":403,\"message\":\"Request had insufficient authentication scopes.\","
                + "\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}");
        PermanentStepException e = assertThrows(PermanentStepException.class, () -> send(Map.of("to", "ada@example.com", "subject", "Hi", "body", "x"), RETRY));
        assertTrue(e.getMessage().contains("Sent folder"), e.getMessage());
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("POST")));
    }

    @Test
    void googlesAnswersAreSorted() {
        answers.put("POST /users/me/messages/send", "401:{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\"}}");
        assertThrows(AccountRejectedException.class, () -> send(Map.of("to", "a@example.com", "subject", "Hi", "body", "x"), FIRST));
        answers.put("POST /users/me/messages/send", "403:{\"error\":{\"code\":403,\"message\":\"Rate\",\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}");
        assertInstanceOf(IllegalStateException.class, assertThrows(Exception.class, () -> send(Map.of("to", "a@example.com", "subject", "Hi", "body", "x"), FIRST)));
        answers.put("POST /users/me/messages/send", "400:{\"error\":{\"code\":400,\"message\":\"Invalid To header\"}}");
        assertEquals("Gmail refused the email: Invalid To header",
                assertThrows(PermanentStepException.class, () -> send(Map.of("to", "a@example.com", "subject", "Hi", "body", "x"), FIRST)).getMessage());
        answers.put("POST /users/me/messages/send", "500:{}");
        assertThrows(UncertainStepException.class, () -> send(Map.of("to", "a@example.com", "subject", "Hi", "body", "x"), FIRST));
    }
}
