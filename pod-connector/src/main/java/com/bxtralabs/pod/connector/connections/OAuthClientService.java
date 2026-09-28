package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.common.ConflictException;
import com.bxtralabs.pod.connector.common.NotFoundException;
import com.bxtralabs.pod.connector.connections.OAuthProviders.Provider;
import com.bxtralabs.pod.connector.model.OAuthClient;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import com.bxtralabs.pod.connector.repository.OAuthClientRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

// Users' own OAuth apps ("bring your own app"): saving them, and picking which client id/secret
// a sign-in or a token refresh uses. The secret is encrypted at rest and never returned.
// There's no way to check a client id/secret on their own; the first sign-in does that.
@Service
public class OAuthClientService {

    private static final String SECRET = "secret";

    private final OAuthClientRepository repository;
    private final ConnectionRepository connections;
    private final OAuthProviders providers;
    private final CredentialCipher cipher;

    public OAuthClientService(OAuthClientRepository repository, ConnectionRepository connections,
                              OAuthProviders providers, CredentialCipher cipher) {
        this.repository = repository;
        this.connections = connections;
        this.providers = providers;
        this.cipher = cipher;
    }

    // What the UI gets. The client id is fine to show (it's in every authorize URL anyway).
    public record OAuthClientView(String id, String provider, String providerName, String name, String clientId,
                                  long connectionCount, Long createdAt, Long updatedAt) {
    }

    // The client id/secret to talk to a provider with.
    public record ClientCredentials(String clientId, String clientSecret) {
    }

    public List<OAuthClientView> list(String userId, String provider) {
        List<OAuthClient> clients = provider == null || provider.isBlank()
                ? repository.findByUserIdOrderByCreatedAtDesc(userId)
                : repository.findByUserIdAndProviderOrderByCreatedAtDesc(userId, provider);
        return clients.stream().map(this::view).toList();
    }

    public OAuthClientView create(String userId, String providerId, String name, String clientId, String clientSecret) {
        requireConfigured();
        Provider provider = providers.find(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown OAuth provider: " + providerId));
        OAuthClient client = new OAuthClient();
        client.setUserId(userId);
        client.setProvider(provider.id());
        client.setName(nameOr(name, provider.displayName() + " app"));
        client.setClientId(required(clientId, "Client ID"));
        client.setClientSecret(cipher.encrypt(Map.of(SECRET, required(clientSecret, "Client secret"))));
        return view(repository.save(client));
    }

    // Rename, and/or replace the secret (e.g. after rotating it with the provider); a blank
    // secret keeps the current one. The client id can't change: tokens belong to the app that
    // issued them, so a different client id is a different app (add a new one instead).
    public OAuthClientView update(String userId, String id, String name, String clientSecret) {
        requireConfigured();
        OAuthClient client = owned(userId, id);
        if (name != null && !name.isBlank()) {
            client.setName(name.trim());
        }
        if (clientSecret != null && !clientSecret.isBlank()) {
            client.setClientSecret(cipher.encrypt(Map.of(SECRET, clientSecret.trim())));
        }
        return view(repository.save(client));
    }

    // Refused while connections still use it: they couldn't refresh their tokens any more.
    public void delete(String userId, String id) {
        OAuthClient client = owned(userId, id);
        long used = connections.countByOauthClientId(id);
        if (used > 0) {
            throw new ConflictException("\"" + client.getName() + "\" is used by " + used + " connection"
                    + (used == 1 ? "" : "s") + ". Delete or reconnect " + (used == 1 ? "it" : "them") + " first.");
        }
        repository.delete(client);
    }

    // Someone else's app looks like it doesn't exist.
    public OAuthClient owned(String userId, String id) {
        return repository.findById(id)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("OAuth app not found: " + id));
    }

    // The user's own app when oauthClientId is set, otherwise the server's app for this provider.
    public ClientCredentials credentialsFor(Provider provider, String oauthClientId) {
        if (oauthClientId == null) {
            if (!provider.configured()) {
                throw new IllegalStateException(provider.displayName()
                        + " sign-in with the server's app isn't set up. Use your own OAuth app.");
            }
            return new ClientCredentials(provider.clientId(), provider.clientSecret());
        }
        OAuthClient client = repository.findById(oauthClientId)
                .orElseThrow(() -> new IllegalStateException("The OAuth app used for this connection was deleted"));
        return new ClientCredentials(client.getClientId(), cipher.decrypt(client.getClientSecret()).get(SECRET));
    }

    private void requireConfigured() {
        if (!cipher.isConfigured()) {
            throw new ConnectionsNotConfiguredException();
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String nameOr(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name.trim();
    }

    private OAuthClientView view(OAuthClient c) {
        String providerName = providers.find(c.getProvider()).map(Provider::displayName).orElse(c.getProvider());
        return new OAuthClientView(c.getId(), c.getProvider(), providerName, c.getName(), c.getClientId(),
                connections.countByOauthClientId(c.getId()), c.getCreatedAt(), c.getUpdatedAt());
    }
}
