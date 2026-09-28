package com.bxtralabs.pod.processor.service.connections;

import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import com.bxtralabs.pod.processor.service.handlers.StepCredentials;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionCredentialsClientTest {

    private HttpServer server;
    private ConnectionCredentialsClient client;
    private final AtomicReference<String> sentToken = new AtomicReference<>();
    private final AtomicReference<String> sentBody = new AtomicReference<>();

    // A stand-in pod-connector: the connection id picks the answer.
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/connections/", ex -> {
            sentToken.set(ex.getRequestHeaders().getFirst("X-Internal-Token"));
            sentBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String id = ex.getRequestURI().getPath().split("/")[3];
            switch (id) {
                case "con_ok" -> respond(ex, 200, ex.getRequestURI().getPath().endsWith("/rejected")
                        ? "{\"marked\":true}"
                        : "{\"connectionId\":\"con_ok\",\"appId\":\"app_github\",\"authType\":\"OAUTH\",\"credentials\":{\"access_token\":\"gho_1\"},\"version\":\"v123\"}");
                case "con_gone" -> respond(ex, 404, "{\"error\":\"Connection not found: con_gone\"}");
                case "con_revoked" -> respond(ex, 409, "{\"error\":\"GitHub needs to be reconnected\",\"code\":\"needs_reauth\"}");
                case "con_badtoken" -> respond(ex, 401, "{\"error\":\"Not allowed\"}");
                default -> respond(ex, 503, "{\"error\":\"GitHub is having trouble\",\"code\":\"temporary\"}");
            }
        });
        server.start();
        client = new ConnectionCredentialsClient(JsonMapper.builder().build(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", "shared-secret-123456");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void fetchesCredentialsForTheWorkflowsOwnerAndApp() throws Exception {
        StepCredentials c = client.fetch("con_ok", "usr_1", "app_github");
        assertEquals("gho_1", c.get("access_token"));
        assertEquals("OAUTH", c.authType());
        assertEquals("shared-secret-123456", sentToken.get());
        assertEquals(Map.of("userId", "usr_1", "appId", "app_github"), JsonMapper.builder().build().readValue(sentBody.get(), Map.class));
    }

    @Test
    void aMissingOrRevokedConnectionFailsTheStepForGood() {
        PermanentStepException gone = assertThrows(PermanentStepException.class, () -> client.fetch("con_gone", "u", "a"));
        assertTrue(gone.getMessage().contains("no longer exists"));
        PermanentStepException revoked = assertThrows(PermanentStepException.class, () -> client.fetch("con_revoked", "u", "a"));
        assertEquals("GitHub needs to be reconnected. Reconnect it on the Connections page, then run again.", revoked.getMessage());
    }

    @Test
    void everythingElseIsRetried() {
        // Not PermanentStepException, so StepExecutor retries the step.
        Exception temporary = assertThrows(IllegalStateException.class, () -> client.fetch("con_flaky", "u", "a"));
        assertTrue(temporary.getMessage().contains("GitHub is having trouble"));
        assertThrows(IllegalStateException.class, () -> client.fetch("con_badtoken", "u", "a"));
        ConnectionCredentialsClient nowhere = new ConnectionCredentialsClient(JsonMapper.builder().build(), "http://127.0.0.1:1", "x");
        assertThrows(IllegalStateException.class, () -> nowhere.fetch("con_ok", "u", "a"));
    }

    @Test
    void reportsRejectedCredentialsByTheirVersion() throws Exception {
        StepCredentials c = client.fetch("con_ok", "usr_1", "app_github");
        assertEquals("v123", c.version());
        assertTrue(client.reportRejected(c, "usr_1", "GitHub no longer accepts this token"));
        assertEquals(Map.of("userId", "usr_1", "appId", "app_github", "version", "v123", "reason", "GitHub no longer accepts this token"),
                JsonMapper.builder().build().readValue(sentBody.get(), Map.class));
        // Best effort: never throws, and without a version there's nothing to report.
        assertFalse(client.reportRejected(new StepCredentials("con_ok", "app_github", "OAUTH", Map.of()), "usr_1", "x"));
        ConnectionCredentialsClient nowhere = new ConnectionCredentialsClient(JsonMapper.builder().build(), "http://127.0.0.1:1", "x");
        assertFalse(nowhere.reportRejected(c, "usr_1", "x"));
    }
}
