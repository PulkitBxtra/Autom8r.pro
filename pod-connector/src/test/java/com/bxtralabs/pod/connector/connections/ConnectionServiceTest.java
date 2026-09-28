package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.common.NotFoundException;
import com.bxtralabs.pod.connector.connections.Connector.CredentialField;
import com.bxtralabs.pod.connector.connections.Connector.TokenAuth;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConnectionServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ConnectionRepository repo = mock(ConnectionRepository.class);
    private final Map<String, Connection> db = new LinkedHashMap<>();
    private final AtomicReference<Map<String, String>> checked = new AtomicReference<>();
    private CredentialCipher cipher;
    private ConnectorRegistry registry;
    private ConnectionService service;

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @BeforeEach
    void setUp() {
        cipher = new CredentialCipher(randomKey(), JSON);
        registry = new ConnectorRegistry(List.of(
                new Connector("app_github", "GitHub", "Repos", new TokenAuth(List.of(
                        new CredentialField("token", "Personal access token", true, true, "", "")), "https://docs",
                        creds -> {
                            checked.set(creds);
                            if (creds.get("token").startsWith("bad")) {
                                throw new ConnectionVerificationException("GitHub rejected these credentials (HTTP 401).");
                            }
                            return "@" + creds.get("token").replace("ghp_", "");
                        }), "github"),
                new Connector("app_gmail", "Gmail", "Email", null, "google")));
        service = new ConnectionService(repo, registry, new OAuthProviders("https://github.com", "", "", "repo", "", "", "http://localhost:8084"), cipher);

        long[] clock = {1000};
        when(repo.save(any(Connection.class))).thenAnswer(inv -> {
            Connection c = inv.getArgument(0);
            if (c.getId() == null) c.setId("con_" + (db.size() + 1));
            if (c.getCreatedAt() == null) c.setCreatedAt(clock[0]++);
            db.put(c.getId(), c);
            return c;
        });
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(db.get((String) inv.getArgument(0))));
        when(repo.findByUserIdOrderByCreatedAtDesc(any())).thenAnswer(inv -> db.values().stream()
                .filter(c -> c.getUserId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparing(Connection::getCreatedAt).reversed()).toList());
        when(repo.findByUserIdAndAppIdOrderByCreatedAtDesc(any(), any())).thenAnswer(inv -> db.values().stream()
                .filter(c -> c.getUserId().equals(inv.getArgument(0)) && c.getAppId().equals(inv.getArgument(1))).toList());
        doAnswer(inv -> db.remove(((Connection) inv.getArgument(0)).getId())).when(repo).delete(any(Connection.class));
    }

    // ---------- connectors ----------

    @Test
    void connectorsDescribeTheFormWithoutVerifiers() {
        List<ConnectionService.ConnectorView> views = service.connectors();

        assertEquals(List.of("app_github", "app_gmail"), views.stream().map(ConnectionService.ConnectorView::appId).toList());
        assertEquals("token", views.get(0).tokenFields().get(0).key());
        assertEquals("https://docs", views.get(0).docsUrl());
        assertNull(views.get(1).tokenFields(), "Gmail is OAuth-only");
        assertTrue(views.get(0).oauthAvailable(), "users can always bring their own GitHub OAuth app");
        assertFalse(views.get(0).platformOAuthAvailable(), "the server's GitHub app isn't configured in this test");
        assertEquals("GitHub", views.get(0).oauthProviderName());
        assertEquals("http://localhost:8084/oauth/callback", views.get(0).callbackUrl());
        assertEquals("https://github.com/settings/developers", views.get(0).oauthSetupUrl());
    }

    // ---------- create ----------

    @Test
    void createChecksTrimsEncryptsAndReturnsNoSecret() {
        ConnectionService.ConnectionView view = service.createWithToken("usr_1", "app_github", Map.of("token", "  ghp_octocat  "));

        assertEquals(Map.of("token", "ghp_octocat"), checked.get(), "trimmed before the provider check");
        assertEquals("@octocat", view.label());
        assertEquals("ACTIVE", view.status());
        assertEquals("TOKEN", view.authType());
        assertEquals("GitHub", view.appName());
        assertFalse(view.toString().contains("ghp_octocat"));

        Connection stored = db.get(view.id());
        assertTrue(stored.getCredentials().startsWith("v1:"));
        assertFalse(stored.getCredentials().contains("ghp_octocat"));
        assertEquals(Map.of("token", "ghp_octocat"), cipher.decrypt(stored.getCredentials()));
    }

    @Test
    void rejectedCredentialsAreNotSaved() {
        assertThrows(ConnectionVerificationException.class,
                () -> service.createWithToken("usr_1", "app_github", Map.of("token", "bad_token")));
        verify(repo, never()).save(any());
    }

    @Test
    void onlyTheAppsOwnFieldsAreKept() {
        service.createWithToken("usr_1", "app_github", Map.of("token", "ghp_a", "extra", "should-not-be-stored"));
        assertEquals(Map.of("token", "ghp_a"), checked.get());
        assertFalse(cipher.decrypt(db.values().iterator().next().getCredentials()).containsKey("extra"));
    }

    @Test
    void requiredFieldsAreEnforcedBeforeCallingTheProvider() {
        assertThrows(IllegalArgumentException.class, () -> service.createWithToken("usr_1", "app_github", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> service.createWithToken("usr_1", "app_github", Map.of("token", "   ")));
        assertThrows(IllegalArgumentException.class, () -> service.createWithToken("usr_1", "app_github", null));
        assertNull(checked.get(), "provider never called");
    }

    @Test
    void oauthOnlyAndUnknownAppsAreRejected() {
        Exception gmail = assertThrows(IllegalArgumentException.class,
                () -> service.createWithToken("usr_1", "app_gmail", Map.of("token", "x")));
        assertTrue(gmail.getMessage().contains("OAuth"));
        assertThrows(IllegalArgumentException.class, () -> service.createWithToken("usr_1", "app_nope", Map.of("token", "x")));
    }

    @Test
    void withoutAKeyNothingIsCheckedOrSaved() {
        ConnectionService unconfigured = new ConnectionService(repo, registry, new OAuthProviders("https://github.com", "", "", "repo", "", "", "http://localhost:8084"), new CredentialCipher("", JSON));
        assertThrows(ConnectionsNotConfiguredException.class,
                () -> unconfigured.createWithToken("usr_1", "app_github", Map.of("token", "ghp_a")));
        assertNull(checked.get(), "fails before a provider round trip");
        verify(repo, never()).save(any());
    }

    // ---------- list ----------

    @Test
    void listIsPerUserNewestFirstAndFiltersByApp() {
        String first = service.createWithToken("usr_1", "app_github", Map.of("token", "ghp_one")).id();
        String second = service.createWithToken("usr_1", "app_github", Map.of("token", "ghp_two")).id();
        service.createWithToken("usr_2", "app_github", Map.of("token", "ghp_theirs"));

        assertEquals(List.of(second, first), service.list("usr_1", null).stream().map(ConnectionService.ConnectionView::id).toList());
        assertEquals(2, service.list("usr_1", "app_github").size());
        assertEquals(0, service.list("usr_1", "app_slack").size());
        assertEquals(2, service.list("usr_1", " ").size(), "blank appId means all");
    }

    // ---------- reconnect ----------

    @Test
    void reconnectKeepsTheIdAndResetsStatus() {
        String id = service.createWithToken("usr_1", "app_github", Map.of("token", "ghp_old")).id();
        Connection c = db.get(id);
        c.setStatus(Connection.STATUS_NEEDS_REAUTH);
        c.setLastError("401 from GitHub");
        String before = c.getCredentials();

        ConnectionService.ConnectionView view = service.replaceToken("usr_1", id, Map.of("token", "ghp_new"));

        assertEquals(id, view.id());
        assertEquals("@new", view.label());
        assertEquals("ACTIVE", view.status());
        assertNull(view.lastError());
        assertNotEquals(before, db.get(id).getCredentials());
        assertEquals(Map.of("token", "ghp_new"), cipher.decrypt(db.get(id).getCredentials()));
    }

    @Test
    void failedReconnectLeavesTheOldCredentialsInPlace() {
        String id = service.createWithToken("usr_1", "app_github", Map.of("token", "ghp_old")).id();
        String before = db.get(id).getCredentials();

        assertThrows(ConnectionVerificationException.class, () -> service.replaceToken("usr_1", id, Map.of("token", "bad_new")));

        assertEquals(before, db.get(id).getCredentials());
    }

    // ---------- ownership / delete ----------

    @Test
    void someoneElsesConnectionLooksNotFound() {
        String id = service.createWithToken("usr_1", "app_github", Map.of("token", "ghp_a")).id();

        assertThrows(NotFoundException.class, () -> service.replaceToken("usr_2", id, Map.of("token", "ghp_x")));
        assertThrows(NotFoundException.class, () -> service.delete("usr_2", id));
        assertTrue(db.containsKey(id));
        assertThrows(NotFoundException.class, () -> service.delete("usr_1", "con_missing"));
    }

    @Test
    void deleteRemovesIt() {
        String id = service.createWithToken("usr_1", "app_github", Map.of("token", "ghp_a")).id();
        service.delete("usr_1", id);
        assertFalse(db.containsKey(id));
        assertTrue(service.list("usr_1", null).isEmpty());
    }
}
