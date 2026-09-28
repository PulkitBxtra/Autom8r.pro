package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.model.OAuthClient;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import com.bxtralabs.pod.connector.repository.OAuthClientRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TokenServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    // Stand-in token endpoint behaviour.
    private volatile String validRefreshToken = "R0";
    private volatile String mode = "rotate"; // rotate | no-rotate | invalid_grant | down | bad_client
    private final AtomicInteger refreshCalls = new AtomicInteger();
    private volatile Map<String, String> lastForm = Map.of();

    private final ConnectionRepository repo = mock(ConnectionRepository.class);
    private final OAuthClientRepository clientRepo = mock(OAuthClientRepository.class);
    private CredentialCipher cipher;
    private TokenService service;
    private Connection connection;

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> form(String raw) {
        Map<String, String> m = new HashMap<>();
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login/oauth/access_token", ex -> {
            Map<String, String> f = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastForm = f;
            refreshCalls.incrementAndGet();
            switch (mode) {
                case "down" -> respond(ex, 503, "{\"error\":\"temporarily_unavailable\"}");
                case "invalid_grant" -> respond(ex, 400, "{\"error\":\"invalid_grant\"}");
                // GitHub answers a wrong client secret with 200 + this error.
                case "bad_client" -> respond(ex, 200, "{\"error\":\"incorrect_client_credentials\"}");
                default -> {
                    if (!f.get("refresh_token").equals(validRefreshToken)) {
                        respond(ex, 400, "{\"error\":\"invalid_grant\"}");
                        return;
                    }
                    int n = refreshCalls.get();
                    String body = "no-rotate".equals(mode)
                            ? "{\"access_token\":\"A" + n + "\",\"expires_in\":3600}"
                            : "{\"access_token\":\"A" + n + "\",\"refresh_token\":\"R" + n + "\",\"expires_in\":3600}";
                    if (!"no-rotate".equals(mode)) validRefreshToken = "R" + n; // old one now invalid
                    respond(ex, 200, body);
                }
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        cipher = new CredentialCipher(Base64.getEncoder().encodeToString(key), JSON);
        ConnectorRegistry registry = new ConnectorRegistry(List.of(
                new Connector("app_github", "GitHub", "", null, "github"),
                new Connector("app_stripe", "Stripe", "", null, null)));
        OAuthProviders providers = new OAuthProviders(base, "client-1", "secret-1", "repo", "", "", "http://localhost:8084");
        OAuthClientService clients = new OAuthClientService(clientRepo, repo, providers, cipher);
        service = new TokenService(repo, registry, providers, clients, cipher, new ProviderHttp(JSON), 60_000);

        OAuthClient own = new OAuthClient();
        own.setId("oac_1");
        own.setUserId("usr_1");
        own.setProvider("github");
        own.setName("My GitHub App");
        own.setClientId("own-client");
        own.setClientSecret(cipher.encrypt(Map.of("secret", "own-secret")));
        when(clientRepo.findById("oac_1")).thenReturn(Optional.of(own));

        connection = new Connection();
        connection.setId("con_1");
        connection.setUserId("usr_1");
        connection.setAppId("app_github");
        connection.setAuthType(Connection.AUTH_OAUTH);
        connection.setStatus(Connection.STATUS_ACTIVE);
        connection.setCredentials(cipher.encrypt(Map.of("access_token", "A0", "refresh_token", "R0")));
        when(repo.findByIdForUpdate("con_1")).thenAnswer(inv -> Optional.of(connection));
        when(repo.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Map<String, String> stored() {
        return cipher.decrypt(connection.getCredentials());
    }

    @Test
    void freshTokenIsReturnedWithoutRefreshing() {
        connection.setExpiresAt(System.currentTimeMillis() + 30 * 60_000);

        assertEquals("A0", service.getValidCredentials("con_1").get("access_token"));
        assertEquals(0, refreshCalls.get());
        assertNotNull(connection.getLastUsedAt());
    }

    @Test
    void expiringTokenIsRefreshedAndTheRotatedRefreshTokenSaved() {
        long before = System.currentTimeMillis();
        connection.setExpiresAt(before + 30_000); // within the 60s margin

        Map<String, String> creds = service.getValidCredentials("con_1");

        assertEquals("A1", creds.get("access_token"));
        assertEquals(Map.of("access_token", "A1", "refresh_token", "R1"), stored(), "rotated token persisted, encrypted");
        assertTrue(connection.getExpiresAt() >= before + 3_600_000);
        assertNotNull(connection.getLastRefreshedAt());
        assertEquals(0, connection.getRefreshFailures());
        assertEquals(Map.of("grant_type", "refresh_token", "refresh_token", "R0", "client_id", "client-1", "client_secret", "secret-1"), lastForm);
    }

    @Test
    void providerThatDoesNotRotateKeepsTheOldRefreshToken() {
        mode = "no-rotate";
        connection.setExpiresAt(System.currentTimeMillis() - 1);

        service.getValidCredentials("con_1");

        assertEquals(Map.of("access_token", "A1", "refresh_token", "R0"), stored());
    }

    @Test
    void refreshingTwiceInARowUsesTheNewRefreshToken() {
        connection.setExpiresAt(System.currentTimeMillis() - 1);
        service.getValidCredentials("con_1");
        connection.setExpiresAt(System.currentTimeMillis() - 1);

        assertEquals("A2", service.getValidCredentials("con_1").get("access_token"));
        assertEquals("R1", lastForm.get("refresh_token"), "second refresh must send the rotated token, not R0");
    }

    @Test
    void invalidGrantMarksNeedsReauthAndStops() {
        mode = "invalid_grant";
        connection.setExpiresAt(System.currentTimeMillis() - 1);

        assertThrows(ConnectionNeedsReauthException.class, () -> service.getValidCredentials("con_1"));
        assertEquals(Connection.STATUS_NEEDS_REAUTH, connection.getStatus());
        assertTrue(connection.getLastError().contains("invalid_grant"));

        int calls = refreshCalls.get();
        assertThrows(ConnectionNeedsReauthException.class, () -> service.getValidCredentials("con_1"));
        assertEquals(calls, refreshCalls.get(), "no more provider calls once it needs reauth");
    }

    @Test
    void providerDownButTokenStillValidKeepsWorkingAndBacksOff() {
        mode = "down";
        long now = System.currentTimeMillis();
        connection.setExpiresAt(now + 30_000);

        assertEquals("A0", service.getValidCredentials("con_1").get("access_token"));
        assertEquals(1, connection.getRefreshFailures());
        assertTrue(connection.getNextRefreshAt() >= now + 60_000 && connection.getNextRefreshAt() < now + 70_000, "1 minute backoff");
        assertEquals(Connection.STATUS_ACTIVE, connection.getStatus());
    }

    @Test
    void providerDownAndTokenExpiredIsATemporaryFailureWithGrowingBackoff() {
        mode = "down";
        connection.setExpiresAt(System.currentTimeMillis() - 1);

        assertThrows(TokenRefreshException.class, () -> service.getValidCredentials("con_1"));
        assertThrows(TokenRefreshException.class, () -> service.getValidCredentials("con_1"));
        long now = System.currentTimeMillis();
        assertEquals(2, connection.getRefreshFailures());
        assertTrue(connection.getNextRefreshAt() >= now + 110_000, "second failure backs off ~2 minutes");
        assertEquals(Connection.STATUS_ACTIVE, connection.getStatus(), "temporary problems don't need a reconnect");

        mode = "rotate";
        assertEquals("A3", service.getValidCredentials("con_1").get("access_token"));
        assertEquals(0, connection.getRefreshFailures(), "success clears the backoff");
        assertNull(connection.getNextRefreshAt());
    }

    @Test
    void missingRefreshTokenNeedsReauth() {
        connection.setCredentials(cipher.encrypt(Map.of("access_token", "A0")));
        connection.setExpiresAt(System.currentTimeMillis() - 1);

        assertThrows(ConnectionNeedsReauthException.class, () -> service.getValidCredentials("con_1"));
        assertEquals(Connection.STATUS_NEEDS_REAUTH, connection.getStatus());
        assertEquals(0, refreshCalls.get());
    }

    @Test
    void tokenConnectionsAndNonExpiringOAuthTokensAreNeverRefreshed() {
        connection.setAuthType(Connection.AUTH_TOKEN);
        connection.setExpiresAt(System.currentTimeMillis() - 1);
        service.getValidCredentials("con_1");

        connection.setAuthType(Connection.AUTH_OAUTH);
        connection.setExpiresAt(null); // e.g. GitHub OAuth App tokens
        service.getValidCredentials("con_1");

        assertEquals(0, refreshCalls.get());
    }

    @Test
    void expiresInParsing() {
        assertEquals(1_000 + 3_600_000L, TokenService.expiresAt(Map.of("expires_in", 3600), 1_000));
        assertEquals(1_000 + 60_000L, TokenService.expiresAt(Map.of("expires_in", "60"), 1_000));
        assertNull(TokenService.expiresAt(Map.of(), 1_000));
        assertNull(TokenService.expiresAt(Map.of("expires_in", "soon"), 1_000));
    }

    // ---------- the user's own OAuth app ----------

    @Test
    void connectionFromTheirOwnAppRefreshesWithThatAppsClient() {
        connection.setOauthClientId("oac_1");
        connection.setExpiresAt(System.currentTimeMillis() - 1);

        assertEquals("A1", service.getValidCredentials("con_1").get("access_token"));
        assertEquals("own-client", lastForm.get("client_id"));
        assertEquals("own-secret", lastForm.get("client_secret"));
    }

    @Test
    void theirAppRejectingItsSecretNeedsTheUserToFixIt() {
        mode = "bad_client";
        connection.setOauthClientId("oac_1");
        connection.setExpiresAt(System.currentTimeMillis() - 1);

        assertThrows(ConnectionNeedsReauthException.class, () -> service.getValidCredentials("con_1"));
        assertEquals(Connection.STATUS_NEEDS_REAUTH, connection.getStatus());
        assertTrue(connection.getLastError().contains("your OAuth app's client ID or secret"), connection.getLastError());
    }

    @Test
    void theServersAppRejectedIsTemporarySinceFixingTheConfigFixesEveryone() {
        mode = "bad_client";
        connection.setExpiresAt(System.currentTimeMillis() - 1);

        assertThrows(TokenRefreshException.class, () -> service.getValidCredentials("con_1"));
        assertEquals(Connection.STATUS_ACTIVE, connection.getStatus());
        assertEquals(1, connection.getRefreshFailures());
    }

    @Test
    void deletedOwnAppNeedsAReconnect() {
        connection.setOauthClientId("oac_gone");
        connection.setExpiresAt(System.currentTimeMillis() - 1);

        assertThrows(ConnectionNeedsReauthException.class, () -> service.getValidCredentials("con_1"));
        assertEquals(Connection.STATUS_NEEDS_REAUTH, connection.getStatus());
        assertEquals(0, refreshCalls.get());
    }

    // ---- C6: an app rejecting the credentials mid-run ----

    @Test
    void aRejectionOfTheCurrentCredentialsMarksTheConnectionForReconnecting() {
        String version = TokenService.version(connection);
        assertTrue(service.markRejected("con_1", "usr_1", "app_github", version, "GitHub no longer accepts this token"));
        assertEquals(Connection.STATUS_NEEDS_REAUTH, connection.getStatus());
        assertEquals("GitHub no longer accepts this token", connection.getLastError());
        // Later steps stop early instead of calling the app with a dead token.
        assertThrows(ConnectionNeedsReauthException.class, () -> service.getValidCredentials("con_1"));
    }

    @Test
    void aRejectionOfOlderCredentialsIsIgnored() {
        String before = TokenService.version(connection);
        // The user reconnected (or the token was refreshed) after the step fetched its token.
        connection.setCredentials(cipher.encrypt(Map.of("access_token", "A1", "refresh_token", "R1")));
        assertNotEquals(before, TokenService.version(connection));
        assertFalse(service.markRejected("con_1", "usr_1", "app_github", before, "old token rejected"));
        assertEquals(Connection.STATUS_ACTIVE, connection.getStatus());
    }

    @Test
    void onlyTheOwnersStepCanReportAndTheVersionRevealsNothing() {
        String version = TokenService.version(connection);
        assertFalse(service.markRejected("con_1", "usr_2", "app_github", version, "x"));
        assertFalse(service.markRejected("con_1", "usr_1", "app_slack", version, "x"));
        assertFalse(service.markRejected("con_missing", "usr_1", "app_github", version, "x"));
        assertEquals(Connection.STATUS_ACTIVE, connection.getStatus());
        assertEquals(24, version.length());
        assertFalse(connection.getCredentials().contains(version));
    }
}
