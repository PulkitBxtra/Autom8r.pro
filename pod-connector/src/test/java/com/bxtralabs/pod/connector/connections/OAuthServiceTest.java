package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.common.NotFoundException;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.model.OAuthClient;
import com.bxtralabs.pod.connector.model.OAuthState;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import com.bxtralabs.pod.connector.repository.OAuthClientRepository;
import com.bxtralabs.pod.connector.repository.OAuthStateRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OAuthServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private volatile Map<String, String> lastTokenForm = Map.of();
    private volatile String tokenResponse = "{\"access_token\":\"gho_abc\",\"refresh_token\":\"ghr_xyz\",\"expires_in\":28800,\"scope\":\"repo,read:user\"}";

    private final OAuthStateRepository states = mock(OAuthStateRepository.class);
    private final Map<String, OAuthState> stateDb = new HashMap<>();
    private final ConnectionRepository connectionRepo = mock(ConnectionRepository.class);
    private final Map<String, Connection> connectionDb = new LinkedHashMap<>();
    private final OAuthClientRepository clientRepo = mock(OAuthClientRepository.class);
    private final Map<String, OAuthClient> clientDb = new LinkedHashMap<>();
    private CredentialCipher cipher;
    private ConnectorRegistry registry;
    private ProviderHttp http;
    private String base;
    private OAuthClientService clients;
    private OAuthService oauth;

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
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
            lastTokenForm = query(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, tokenResponse);
        });
        server.createContext("/api/user", ex -> {
            boolean ok = "Bearer gho_abc".equals(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, ok ? 200 : 401, ok ? "{\"login\":\"octocat\"}" : "{}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        cipher = new CredentialCipher(Base64.getEncoder().encodeToString(key), JSON);
        http = new ProviderHttp(JSON);
        registry = new ConnectorRegistry(ConnectorRegistry.defaults(http, new ConnectorRegistry.Endpoints(
                base + "/api", "x", "x", "x", "x", "x", "x")));
        oauth = service("client-1", "secret-1");

        when(states.save(any(OAuthState.class))).thenAnswer(inv -> {
            OAuthState s = inv.getArgument(0);
            stateDb.put(s.getId(), s);
            return s;
        });
        when(states.findById(any())).thenAnswer(inv -> Optional.ofNullable(stateDb.get((String) inv.getArgument(0))));
        when(states.consume(any())).thenAnswer(inv -> stateDb.remove((String) inv.getArgument(0)) == null ? 0 : 1);
        when(connectionRepo.save(any(Connection.class))).thenAnswer(inv -> {
            Connection c = inv.getArgument(0);
            if (c.getId() == null) c.setId("con_" + (connectionDb.size() + 1));
            connectionDb.put(c.getId(), c);
            return c;
        });
        when(connectionRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(connectionDb.get((String) inv.getArgument(0))));
        when(clientRepo.save(any(OAuthClient.class))).thenAnswer(inv -> {
            OAuthClient c = inv.getArgument(0);
            if (c.getId() == null) c.setId("oac_" + (clientDb.size() + 1));
            clientDb.put(c.getId(), c);
            return c;
        });
        when(clientRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(clientDb.get((String) inv.getArgument(0))));
    }

    // platform client id/secret blank = the server's own GitHub app isn't configured.
    private OAuthService service(String platformClientId, String platformSecret) {
        OAuthProviders providers = new OAuthProviders(base, platformClientId, platformSecret, "read:user repo", "", "",
                "http://localhost:8084/");
        clients = new OAuthClientService(clientRepo, connectionRepo, providers, cipher);
        ConnectionService connections = new ConnectionService(connectionRepo, registry, providers, cipher);
        return new OAuthService(registry, providers, states, connections, clients, cipher, http);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Map<String, String> startParams() {
        URI url = URI.create(oauth.start("usr_1", "app_github", null, null));
        return query(url.getRawQuery());
    }

    // ---------- start ----------

    @Test
    void startBuildsAPkceAuthorizeUrl() {
        String url = oauth.start("usr_1", "app_github", null, null);
        Map<String, String> p = query(URI.create(url).getRawQuery());

        assertTrue(url.startsWith("http://127.0.0.1:" + server.getAddress().getPort() + "/login/oauth/authorize?"));
        assertEquals("code", p.get("response_type"));
        assertEquals("client-1", p.get("client_id"));
        assertEquals("http://localhost:8084/oauth/callback", p.get("redirect_uri"), "trailing slash of public-url handled");
        assertEquals("read:user repo", p.get("scope"));
        assertEquals("S256", p.get("code_challenge_method"));

        OAuthState saved = stateDb.get(p.get("state"));
        assertNotNull(saved, "state remembered");
        assertEquals("usr_1", saved.getUserId());
        assertTrue(p.get("state").length() >= 40, "state is long and random");
        String verifier = cipher.decrypt(saved.getCodeVerifier()).get("v");
        assertEquals(OAuthService.challenge(verifier), p.get("code_challenge"), "challenge = S256(verifier)");
        assertFalse(url.contains(verifier), "the verifier never leaves the server");
        verify(states).deleteOlderThan(anyLong());
    }

    @Test
    void startRejectsAppsWithoutConfiguredOAuth() {
        assertThrows(IllegalArgumentException.class, () -> oauth.start("usr_1", "app_slack", null, null), "no OAuth for Slack");
        Exception google = assertThrows(IllegalArgumentException.class, () -> oauth.start("usr_1", "app_gmail", null, null));
        assertTrue(google.getMessage().contains("isn't set up"));
        assertThrows(IllegalArgumentException.class, () -> oauth.start("usr_1", "app_nope", null, null));
    }

    @Test
    void pkceChallengeMatchesTheRfcExample() {
        // RFC 7636 appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                OAuthService.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    // ---------- complete ----------

    @Test
    void successfulSignInExchangesTheCodeAndSavesAnOAuthConnection() {
        Map<String, String> p = startParams();
        String verifier = cipher.decrypt(stateDb.get(p.get("state")).getCodeVerifier()).get("v");
        long before = System.currentTimeMillis();

        OAuthService.Result result = oauth.complete("code-123", p.get("state"), null, null);

        assertTrue(result.success(), String.valueOf(result.message()));
        assertEquals("authorization_code", lastTokenForm.get("grant_type"));
        assertEquals("code-123", lastTokenForm.get("code"));
        assertEquals(verifier, lastTokenForm.get("code_verifier"), "PKCE verifier sent with the exchange");
        assertEquals("secret-1", lastTokenForm.get("client_secret"));
        assertEquals("http://localhost:8084/oauth/callback", lastTokenForm.get("redirect_uri"));

        Connection c = connectionDb.get(result.connectionId());
        assertEquals("usr_1", c.getUserId());
        assertEquals(Connection.AUTH_OAUTH, c.getAuthType());
        assertEquals("@octocat", c.getLabel(), "named with GitHub /user using the new access token");
        assertEquals("repo,read:user", c.getScopes());
        assertTrue(c.getExpiresAt() >= before + 28_800_000L);
        assertEquals(Map.of("access_token", "gho_abc", "refresh_token", "ghr_xyz"), cipher.decrypt(c.getCredentials()));
        assertFalse(stateDb.containsKey(p.get("state")), "state consumed");
    }

    @Test
    void stateIsSingleUse() {
        Map<String, String> p = startParams();
        assertTrue(oauth.complete("code-1", p.get("state"), null, null).success());

        OAuthService.Result replay = oauth.complete("code-1", p.get("state"), null, null);
        assertFalse(replay.success());
        assertTrue(replay.message().contains("already used"));
        assertEquals(1, connectionDb.size());
    }

    @Test
    void unknownOrMissingStateIsRejected() {
        assertFalse(oauth.complete("code", "forged-state", null, null).success());
        assertFalse(oauth.complete("code", null, null, null).success());
        assertTrue(connectionDb.isEmpty());
    }

    @Test
    void expiredStateIsRejected() {
        stateDb.put("old", new OAuthState("old", "usr_1", "app_github", "github", null, null,
                cipher.encrypt(Map.of("v", "x")), System.currentTimeMillis() - OAuthService.STATE_TTL_MS - 1));

        OAuthService.Result r = oauth.complete("code", "old", null, null);
        assertFalse(r.success());
        assertTrue(r.message().contains("expired"));
    }

    @Test
    void userCancellingIsReportedPlainly() {
        Map<String, String> p = startParams();
        OAuthService.Result r = oauth.complete(null, p.get("state"), "access_denied", "The user has denied your application access.");
        assertFalse(r.success());
        assertEquals("Sign-in was cancelled.", r.message());
        assertEquals("app_github", r.appId());
    }

    @Test
    void providerRejectingTheCodeFails() {
        tokenResponse = "{\"error\":\"bad_verification_code\",\"error_description\":\"The code passed is incorrect or expired.\"}";
        Map<String, String> p = startParams();

        OAuthService.Result r = oauth.complete("stale-code", p.get("state"), null, null);

        assertFalse(r.success());
        assertTrue(r.message().contains("bad_verification_code"), r.message());
        assertTrue(connectionDb.isEmpty());
    }

    @Test
    void reconnectUpdatesTheSameConnection() {
        Connection existing = new Connection();
        existing.setId("con_existing");
        existing.setUserId("usr_1");
        existing.setAppId("app_github");
        existing.setAuthType(Connection.AUTH_TOKEN);
        existing.setStatus(Connection.STATUS_NEEDS_REAUTH);
        existing.setCredentials("v1:old");
        connectionDb.put("con_existing", existing);

        String url = oauth.start("usr_1", "app_github", "con_existing", null);
        OAuthService.Result r = oauth.complete("code", query(URI.create(url).getRawQuery()).get("state"), null, null);

        assertEquals("con_existing", r.connectionId());
        assertEquals(Connection.STATUS_ACTIVE, existing.getStatus());
        assertEquals(Connection.AUTH_OAUTH, existing.getAuthType());
        assertEquals(1, connectionDb.size());
    }

    @Test
    void reconnectingSomeoneElsesConnectionIsRefused() {
        Connection theirs = new Connection();
        theirs.setId("con_theirs");
        theirs.setUserId("usr_2");
        theirs.setAppId("app_github");
        connectionDb.put("con_theirs", theirs);

        assertThrows(RuntimeException.class, () -> oauth.start("usr_1", "app_github", "con_theirs", null));
    }

    // ---------- the user's own OAuth app ----------

    @Test
    void signingInWithTheirOwnAppUsesItsClientIdAndSecretThroughout() {
        String clientId = clients.create("usr_1", "github", "My GitHub App", "own-client", "own-secret").id();

        Map<String, String> p = query(URI.create(oauth.start("usr_1", "app_github", null, clientId)).getRawQuery());
        assertEquals("own-client", p.get("client_id"), "authorize URL carries their client id");
        assertEquals("http://localhost:8084/oauth/callback", p.get("redirect_uri"), "same callback URL as the server's app");
        assertEquals(clientId, stateDb.get(p.get("state")).getOauthClientId());

        OAuthService.Result r = oauth.complete("code-1", p.get("state"), null, null);

        assertTrue(r.success(), String.valueOf(r.message()));
        assertEquals("own-client", lastTokenForm.get("client_id"));
        assertEquals("own-secret", lastTokenForm.get("client_secret"), "code exchanged with their secret");
        assertEquals(clientId, connectionDb.get(r.connectionId()).getOauthClientId(), "refreshes will use their app too");
    }

    @Test
    void theirOwnAppWorksWhenTheServersAppIsNotConfigured() {
        oauth = service("", "");
        assertThrows(IllegalArgumentException.class, () -> oauth.start("usr_1", "app_github", null, null));

        String clientId = clients.create("usr_1", "github", null, "own-client", "own-secret").id();
        Map<String, String> p = query(URI.create(oauth.start("usr_1", "app_github", null, clientId)).getRawQuery());

        assertTrue(oauth.complete("code-1", p.get("state"), null, null).success());
    }

    @Test
    void someoneElsesAppOrAnotherProvidersAppIsRefused() {
        String theirs = clients.create("usr_2", "github", null, "their-client", "their-secret").id();
        String google = clients.create("usr_1", "google", null, "g-client", "g-secret").id();

        assertThrows(NotFoundException.class, () -> oauth.start("usr_1", "app_github", null, theirs));
        Exception wrong = assertThrows(IllegalArgumentException.class, () -> oauth.start("usr_1", "app_github", null, google));
        assertTrue(wrong.getMessage().contains("not a GitHub OAuth app"), wrong.getMessage());
        assertTrue(stateDb.isEmpty());
    }

    @Test
    void wrongClientSecretIsExplained() {
        tokenResponse = "{\"error\":\"incorrect_client_credentials\"}";
        String clientId = clients.create("usr_1", "github", null, "own-client", "typo").id();
        Map<String, String> p = query(URI.create(oauth.start("usr_1", "app_github", null, clientId)).getRawQuery());

        OAuthService.Result r = oauth.complete("code-1", p.get("state"), null, null);

        assertFalse(r.success());
        assertTrue(r.message().contains("rejected your OAuth app's client ID or secret"), r.message());
        assertTrue(connectionDb.isEmpty());
    }

    @Test
    void callbackUrlMismatchSaysWhichUrlToRegister() {
        String clientId = clients.create("usr_1", "github", null, "own-client", "own-secret").id();
        Map<String, String> p = query(URI.create(oauth.start("usr_1", "app_github", null, clientId)).getRawQuery());

        OAuthService.Result r = oauth.complete(null, p.get("state"), "redirect_uri_mismatch", "The redirect_uri MUST match");

        assertFalse(r.success());
        assertTrue(r.message().contains("http://localhost:8084/oauth/callback"), r.message());
    }
}
