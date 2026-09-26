package com.bxtralabs.pod.processor.service.handlers;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
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

class HandlersTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpRequestHandler http = new HttpRequestHandler(jsonMapper);
    private final ActionHandlerRegistry registry =
            new ActionHandlerRegistry(List.of(new LogHandler(), http, new SimulatedHandler()));

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    private static GraphNode node(String app, String type) {
        return new GraphNode("n1", "action", app, "item", "Do thing", type, Map.of(), null);
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/json", ex -> {
            lastMethod.set(ex.getRequestMethod());
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 201, "{\"ok\":true,\"id\":7}");
        });
        server.createContext("/text", ex -> respond(ex, 200, "plain text"));
        server.createContext("/boom", ex -> respond(ex, 500, "database on fire"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------- registry ----------

    @Test
    void registryPicksRealHandlersAndFallsBackToSimulated() {
        assertInstanceOf(HttpRequestHandler.class, registry.handlerFor(node("HTTP", "http_request")));
        assertInstanceOf(LogHandler.class, registry.handlerFor(node("Log", "log")));
        assertInstanceOf(SimulatedHandler.class, registry.handlerFor(node("Gmail", "action")));
        assertInstanceOf(SimulatedHandler.class, registry.handlerFor(node("Trello", null)));
    }

    // ---------- simulated / log ----------

    @Test
    void simulatedEchoesInput() {
        Map<String, Object> out = new SimulatedHandler().execute(node("Gmail", "action"), Map.of("to", "a@b.co"));
        assertEquals(true, out.get("simulated"));
        assertEquals("Gmail", out.get("app"));
        assertEquals(Map.of("to", "a@b.co"), out.get("input"));
    }

    @Test
    void logReturnsWhatItLogged() {
        assertEquals(Map.of("logged", Map.of("x", 1)), new LogHandler().execute(node("Log", "log"), Map.of("x", 1)));
    }

    // ---------- http_request ----------

    @Test
    void postsJsonBodyAndParsesJsonResponse() throws Exception {
        Map<String, Object> out = http.execute(node("HTTP", "http_request"), Map.of(
                "url", baseUrl + "/json",
                "method", "post",
                "headers", Map.of("Authorization", "Bearer abc"),
                "body", Map.of("order", 42)));

        assertEquals("POST", lastMethod.get());
        assertEquals("{\"order\":42}", lastBody.get());
        assertEquals("application/json", lastContentType.get());
        assertEquals("Bearer abc", lastAuth.get());
        assertEquals(201, out.get("status"));
        assertEquals(Map.of("ok", true, "id", 7), out.get("body"));
    }

    @Test
    void defaultsToGetWithNoBody() throws Exception {
        http.execute(node("HTTP", "http_request"), Map.of("url", baseUrl + "/json"));
        assertEquals("GET", lastMethod.get());
        assertEquals("", lastBody.get());
    }

    @Test
    void stringBodyIsSentAsIsAndExplicitContentTypeWins() throws Exception {
        http.execute(node("HTTP", "http_request"), Map.of(
                "url", baseUrl + "/json", "method", "PUT",
                "headers", Map.of("content-type", "text/plain"), "body", "hello"));
        assertEquals("hello", lastBody.get());
        assertEquals("text/plain", lastContentType.get());
    }

    @Test
    void nonJsonResponseIsReturnedAsText() throws Exception {
        assertEquals("plain text", http.execute(node("HTTP", "http_request"), Map.of("url", baseUrl + "/text")).get("body"));
    }

    @Test
    void errorStatusFailsTheStepWithStatusAndBodyPreview() {
        Exception e = assertThrows(IllegalStateException.class,
                () -> http.execute(node("HTTP", "http_request"), Map.of("url", baseUrl + "/boom")));
        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
        assertTrue(e.getMessage().contains("database on fire"), e.getMessage());
    }

    @Test
    void missingUrlFails() {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> http.execute(node("HTTP", "http_request"), Map.of()));
        assertTrue(e.getMessage().contains("url"));
    }

    @Test
    void unreachableHostFails() {
        assertThrows(Exception.class, () -> http.execute(node("HTTP", "http_request"),
                Map.of("url", "http://127.0.0.1:1/nothing", "timeoutSeconds", 2)));
    }
}
