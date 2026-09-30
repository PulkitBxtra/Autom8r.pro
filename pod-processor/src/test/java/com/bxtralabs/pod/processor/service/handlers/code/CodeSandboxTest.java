package com.bxtralabs.pod.processor.service.handlers.code;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CodeSandboxTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HttpServer server;
    private String base;
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            token.set(ex.getRequestHeaders().getFirst("X-Sandbox-Token"));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
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

    @Test
    void sendsTheScriptWithItsTokenAndReadsTheOutcome() throws Exception {
        answer = "{\"ok\":true,\"result\":{\"total\":42},\"logs\":\"hi\\n\"}";
        CodeSandbox.Result r = new CodeSandbox(JSON, base + "/", "s3cret").run("[total: a * 2]", Map.of("a", 21), 7);
        assertTrue(r.ok());
        assertEquals(Map.of("total", 42), r.result());
        assertEquals("hi\n", r.logs());
        assertEquals(Map.of("script", "[total: a * 2]", "bindings", Map.of("a", 21), "timeoutSeconds", 7), JSON.readValue(received.get(), Map.class));
        assertEquals("s3cret", token.get());
    }

    @Test
    void scriptErrorsComeBackAsResults() throws Exception {
        answer = "{\"ok\":false,\"kind\":\"script\",\"error\":\"No such property: amout\",\"line\":3,\"logs\":\"\"}";
        CodeSandbox.Result r = new CodeSandbox(JSON, base, "").run("x", Map.of(), 5);
        assertFalse(r.ok());
        assertEquals("script", r.kind());
        assertEquals(3, r.line());
        assertNull(token.get(), "no token header when none is configured");
    }

    @Test
    void busyUnreachableOrBrokenIsUnavailableSoTheStepIsRetried() throws Exception {
        for (int s : List.of(503, 401)) {
            status = s;
            answer = "{\"error\":\"busy\"}";
            assertThrows(CodeSandbox.SandboxUnavailableException.class, () -> new CodeSandbox(JSON, base, "").run("x", Map.of(), 5));
        }
        status = 200;
        answer = "<html>";
        assertThrows(CodeSandbox.SandboxUnavailableException.class, () -> new CodeSandbox(JSON, base, "").run("x", Map.of(), 5));
        server.stop(0);
        CodeSandbox.SandboxUnavailableException e = assertThrows(CodeSandbox.SandboxUnavailableException.class,
                () -> new CodeSandbox(JSON, base, "").run("x", Map.of(), 5));
        assertTrue(e.getMessage().contains("can't be reached"), e.getMessage());
    }
}
