package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.common.ConflictException;
import com.bxtralabs.pod.connector.common.NotFoundException;
import com.bxtralabs.pod.connector.model.OAuthClient;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import com.bxtralabs.pod.connector.repository.OAuthClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OAuthClientServiceTest {

    private final OAuthClientRepository repo = mock(OAuthClientRepository.class);
    private final ConnectionRepository connections = mock(ConnectionRepository.class);
    private final Map<String, OAuthClient> db = new LinkedHashMap<>();
    private final OAuthProviders providers = new OAuthProviders("https://github.com", "server-client", "server-secret",
            "repo", "", "", "http://localhost:8084");
    private CredentialCipher cipher;
    private OAuthClientService service;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        cipher = new CredentialCipher(Base64.getEncoder().encodeToString(key), JsonMapper.builder().build());
        service = new OAuthClientService(repo, connections, providers, cipher);

        long[] clock = {1000};
        when(repo.save(any(OAuthClient.class))).thenAnswer(inv -> {
            OAuthClient c = inv.getArgument(0);
            if (c.getId() == null) c.setId("oac_" + (db.size() + 1));
            if (c.getCreatedAt() == null) c.setCreatedAt(clock[0]++);
            db.put(c.getId(), c);
            return c;
        });
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(db.get((String) inv.getArgument(0))));
        when(repo.findByUserIdOrderByCreatedAtDesc(any())).thenAnswer(inv -> db.values().stream()
                .filter(c -> c.getUserId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparing(OAuthClient::getCreatedAt).reversed()).toList());
        when(repo.findByUserIdAndProviderOrderByCreatedAtDesc(any(), any())).thenAnswer(inv -> db.values().stream()
                .filter(c -> c.getUserId().equals(inv.getArgument(0)) && c.getProvider().equals(inv.getArgument(1))).toList());
        doAnswer(inv -> db.remove(((OAuthClient) inv.getArgument(0)).getId())).when(repo).delete(any(OAuthClient.class));
    }

    @Test
    void createTrimsEncryptsTheSecretAndNeverReturnsIt() {
        OAuthClientService.OAuthClientView view = service.create("usr_1", "github", "  Acme app ", " Iv1.abc ", " s3cret ");

        assertEquals("Acme app", view.name());
        assertEquals("Iv1.abc", view.clientId());
        assertEquals("GitHub", view.providerName());
        assertEquals(0, view.connectionCount());
        assertFalse(view.toString().contains("s3cret"));

        OAuthClient stored = db.get(view.id());
        assertTrue(stored.getClientSecret().startsWith("v1:"));
        assertFalse(stored.getClientSecret().contains("s3cret"));
        assertEquals("s3cret", service.credentialsFor(providers.find("github").orElseThrow(), view.id()).clientSecret());
    }

    @Test
    void nameDefaultsToTheProvider() {
        assertEquals("GitHub app", service.create("usr_1", "github", " ", "id", "secret").name());
    }

    @Test
    void clientIdSecretAndAKnownProviderAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> service.create("usr_1", "github", null, " ", "secret"));
        assertThrows(IllegalArgumentException.class, () -> service.create("usr_1", "github", null, "id", null));
        assertThrows(IllegalArgumentException.class, () -> service.create("usr_1", "gitlab", null, "id", "secret"));
        verify(repo, never()).save(any());
    }

    @Test
    void withoutAnEncryptionKeyNothingIsSaved() {
        OAuthClientService unconfigured = new OAuthClientService(repo, connections, providers,
                new CredentialCipher("", JsonMapper.builder().build()));
        assertThrows(ConnectionsNotConfiguredException.class,
                () -> unconfigured.create("usr_1", "github", null, "id", "secret"));
        verify(repo, never()).save(any());
    }

    @Test
    void listIsPerUserAndFiltersByProvider() {
        String gh = service.create("usr_1", "github", null, "id1", "s").id();
        String google = service.create("usr_1", "google", null, "id2", "s").id();
        service.create("usr_2", "github", null, "id3", "s");

        assertEquals(List.of(google, gh), service.list("usr_1", null).stream().map(OAuthClientService.OAuthClientView::id).toList());
        assertEquals(List.of(gh), service.list("usr_1", "github").stream().map(OAuthClientService.OAuthClientView::id).toList());
    }

    @Test
    void updateRenamesAndRotatesTheSecretButBlankKeepsIt() {
        String id = service.create("usr_1", "github", "Old", "id", "first").id();
        OAuthProviders.Provider github = providers.find("github").orElseThrow();

        service.update("usr_1", id, null, "  ");
        assertEquals("first", service.credentialsFor(github, id).clientSecret(), "blank secret keeps the current one");

        OAuthClientService.OAuthClientView view = service.update("usr_1", id, "New", "second");
        assertEquals("New", view.name());
        assertEquals("id", view.clientId(), "client id never changes");
        assertEquals("second", service.credentialsFor(github, id).clientSecret());
    }

    @Test
    void someoneElsesAppLooksNotFound() {
        String id = service.create("usr_1", "github", null, "id", "secret").id();

        assertThrows(NotFoundException.class, () -> service.update("usr_2", id, "x", "y"));
        assertThrows(NotFoundException.class, () -> service.delete("usr_2", id));
        assertTrue(db.containsKey(id));
    }

    @Test
    void anAppInUseCantBeDeleted() {
        String id = service.create("usr_1", "github", "Acme", "id", "secret").id();
        when(connections.countByOauthClientId(id)).thenReturn(2L);

        Exception e = assertThrows(ConflictException.class, () -> service.delete("usr_1", id));
        assertTrue(e.getMessage().contains("2 connections"), e.getMessage());
        assertTrue(db.containsKey(id));

        when(connections.countByOauthClientId(id)).thenReturn(0L);
        service.delete("usr_1", id);
        assertFalse(db.containsKey(id));
    }

    @Test
    void credentialsForPicksTheServersAppWhenNoOwnAppIsGiven() {
        OAuthProviders.Provider github = providers.find("github").orElseThrow();
        assertEquals(new OAuthClientService.ClientCredentials("server-client", "server-secret"),
                service.credentialsFor(github, null));

        OAuthProviders.Provider google = providers.find("google").orElseThrow(); // server app not configured
        assertThrows(IllegalStateException.class, () -> service.credentialsFor(google, null));
        assertThrows(IllegalStateException.class, () -> service.credentialsFor(github, "oac_missing"));
    }
}
