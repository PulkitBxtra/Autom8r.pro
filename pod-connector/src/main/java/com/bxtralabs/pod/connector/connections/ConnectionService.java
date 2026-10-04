package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.common.NotFoundException;
import com.bxtralabs.pod.connector.connections.Connector.CredentialField;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Listing, creating, reconnecting and deleting a user's connections. Credentials are checked
// with the provider before they're saved, so every connection in the list is known to have
// worked at least once. Nothing returned from here ever contains a credential.
@Service
public class ConnectionService {

    private final ConnectionRepository repository;
    private final ConnectorRegistry registry;
    private final OAuthProviders oauthProviders;
    private final CredentialCipher cipher;

    public ConnectionService(ConnectionRepository repository, ConnectorRegistry registry,
                             OAuthProviders oauthProviders, CredentialCipher cipher) {
        this.repository = repository;
        this.registry = registry;
        this.oauthProviders = oauthProviders;
        this.cipher = cipher;
    }

    // What the UI gets for a connection.
    // oauthClientId: the user's own OAuth app this connection signed in through (null: the server's).
    public record ConnectionView(String id, String appId, String appName, String label, String authType,
                                 String status, String lastError, String oauthClientId,
                                 Long createdAt, Long updatedAt, Long lastUsedAt) {
    }

    // What the UI needs to render "New connection" for an app.
    //  oauthAvailable: the app supports OAuth, so the user can sign in with their own OAuth app.
    //  platformOAuthAvailable: the server's own OAuth app for it is configured too.
    //  oauthProviderName: for the "Connect with GitHub" button.
    //  oauthSetupUrl / callbackUrl: where to create an OAuth app, and the callback URL to register in it.
    //  oauthNeedsWorkspace: signing in is for one workspace, whose ID the user enters first (Trello).
    public record ConnectorView(String appId, String name, String description, List<CredentialField> tokenFields,
                                String docsUrl, String oauthProvider, String oauthProviderName,
                                boolean oauthAvailable, boolean platformOAuthAvailable,
                                String oauthSetupUrl, String callbackUrl, boolean oauthNeedsWorkspace) {
    }

    public List<ConnectorView> connectors() {
        return registry.all().stream().map(c -> {
            OAuthProviders.Provider provider = oauthProviders.find(c.oauthProvider()).orElse(null);
            return new ConnectorView(
                    c.appId(), c.name(), c.description(),
                    c.token() == null ? null : c.token().fields(),
                    c.token() == null ? null : c.token().docsUrl(),
                    c.oauthProvider(),
                    provider == null ? null : provider.displayName(),
                    provider != null,
                    provider != null && provider.configured(),
                    provider == null ? null : provider.setupUrl(),
                    provider == null ? null : oauthProviders.callbackUrl(provider.id()),
                    provider != null && provider.needsWorkspace());
        }).toList();
    }

    public List<ConnectionView> list(String userId, String appId) {
        List<Connection> connections = appId == null || appId.isBlank()
                ? repository.findByUserIdOrderByCreatedAtDesc(userId)
                : repository.findByUserIdAndAppIdOrderByCreatedAtDesc(userId, appId);
        return connections.stream().map(this::view).toList();
    }

    public ConnectionView createWithToken(String userId, String appId, Map<String, String> credentials) {
        requireConfigured();
        Connector connector = tokenConnector(appId);
        Map<String, String> clean = checkedCredentials(connector, credentials);
        String label = connector.token().verifier().verify(clean);

        Connection connection = new Connection();
        connection.setUserId(userId);
        connection.setAppId(appId);
        connection.setAuthType(Connection.AUTH_TOKEN);
        connection.setStatus(Connection.STATUS_ACTIVE);
        connection.setLabel(label);
        connection.setCredentials(cipher.encrypt(clean));
        return view(repository.save(connection));
    }

    // Reconnect with new credentials. Same connection id, so workflows using it keep working.
    public ConnectionView replaceToken(String userId, String connectionId, Map<String, String> credentials) {
        requireConfigured();
        Connection connection = owned(userId, connectionId);
        Connector connector = tokenConnector(connection.getAppId());
        Map<String, String> clean = checkedCredentials(connector, credentials);
        String label = connector.token().verifier().verify(clean);

        connection.setAuthType(Connection.AUTH_TOKEN);
        connection.setLabel(label);
        connection.setCredentials(cipher.encrypt(clean));
        connection.setStatus(Connection.STATUS_ACTIVE);
        connection.setLastError(null);
        connection.setScopes(null);
        connection.setOauthClientId(null);
        connection.setExpiresAt(null);
        connection.setRefreshFailures(0);
        connection.setNextRefreshAt(null);
        return view(repository.save(connection));
    }

    // Saves a new OAuth connection, or refreshes an existing one when reconnecting (same id).
    // oauthClientId: the user's own OAuth app that issued these tokens (null: the server's).
    public ConnectionView saveOAuth(String userId, String appId, String existingConnectionId, String oauthClientId,
                                    String label, Map<String, String> tokens, String scopes, Long expiresAt) {
        requireConfigured();
        Connection connection = existingConnectionId == null ? new Connection() : owned(userId, existingConnectionId);
        connection.setUserId(userId);
        connection.setAppId(appId);
        connection.setAuthType(Connection.AUTH_OAUTH);
        connection.setStatus(Connection.STATUS_ACTIVE);
        connection.setLabel(label);
        connection.setCredentials(cipher.encrypt(tokens));
        connection.setScopes(scopes);
        connection.setOauthClientId(oauthClientId);
        connection.setExpiresAt(expiresAt);
        connection.setLastError(null);
        connection.setRefreshFailures(0);
        connection.setNextRefreshAt(null);
        connection.setLastRefreshedAt(null);
        return view(repository.save(connection));
    }

    public void delete(String userId, String connectionId) {
        repository.delete(owned(userId, connectionId));
    }

    // Someone else's connection looks like it doesn't exist, as with workflows and runs.
    public Connection owned(String userId, String connectionId) {
        return repository.findById(connectionId)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Connection not found: " + connectionId));
    }

    private Connector tokenConnector(String appId) {
        Connector connector = registry.find(appId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown app: " + appId));
        if (connector.token() == null) {
            throw new IllegalArgumentException(connector.name() + " connects with OAuth, not a token");
        }
        return connector;
    }

    // Keeps only this app's own fields (trimmed) and requires the required ones, so nothing
    // unexpected ends up stored alongside the credentials.
    private static Map<String, String> checkedCredentials(Connector connector, Map<String, String> credentials) {
        Map<String, String> clean = new LinkedHashMap<>();
        for (CredentialField field : connector.token().fields()) {
            String value = credentials == null ? null : credentials.get(field.key());
            value = value == null ? null : value.trim();
            if (value == null || value.isEmpty()) {
                if (field.required()) {
                    throw new IllegalArgumentException(field.label() + " is required");
                }
                continue;
            }
            clean.put(field.key(), value);
        }
        return clean;
    }

    // Checked before calling the provider, so a missing key fails fast with 503 instead of
    // after a round trip.
    private void requireConfigured() {
        if (!cipher.isConfigured()) {
            throw new ConnectionsNotConfiguredException();
        }
    }

    private ConnectionView view(Connection c) {
        String appName = registry.find(c.getAppId()).map(Connector::name).orElse(c.getAppId());
        return new ConnectionView(c.getId(), c.getAppId(), appName, c.getLabel(), c.getAuthType(), c.getStatus(),
                c.getLastError(), c.getOauthClientId(), c.getCreatedAt(), c.getUpdatedAt(), c.getLastUsedAt());
    }
}
