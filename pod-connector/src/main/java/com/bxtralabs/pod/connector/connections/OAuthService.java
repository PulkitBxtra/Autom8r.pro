package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.connections.Connector.CredentialField;
import com.bxtralabs.pod.connector.connections.OAuthClientService.ClientCredentials;
import com.bxtralabs.pod.connector.connections.OAuthProviders.Provider;
import com.bxtralabs.pod.connector.connections.ProviderHttp.TokenResponse;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.model.OAuthClient;
import com.bxtralabs.pod.connector.model.OAuthState;
import com.bxtralabs.pod.connector.repository.OAuthStateRepository;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

// "Connect with <provider>": authorization code flow with PKCE, through either the server's
// OAuth app or one of the user's own (OAuthClientService).
//  1. start(): remember who's signing in under a random single-use `state`, plus a PKCE
//     verifier; send the browser to the provider with the state and the verifier's hash.
//  2. The provider sends the browser back to /oauth/callback with a code and the state.
//  3. complete(): consume the state (unknown/used/expired -> rejected), exchange the code
//     (+ client secret + PKCE verifier) for tokens, name the account, save the connection.
@Service
public class OAuthService {

    static final long STATE_TTL_MS = 10 * 60_000L;

    private final ConnectorRegistry registry;
    private final OAuthProviders providers;
    private final OAuthStateRepository states;
    private final ConnectionService connections;
    private final OAuthClientService clients;
    private final CredentialCipher cipher;
    private final ProviderHttp http;
    private final SecureRandom random = new SecureRandom();

    public OAuthService(ConnectorRegistry registry, OAuthProviders providers, OAuthStateRepository states,
                        ConnectionService connections, OAuthClientService clients, CredentialCipher cipher,
                        ProviderHttp http) {
        this.registry = registry;
        this.providers = providers;
        this.states = states;
        this.connections = connections;
        this.clients = clients;
        this.cipher = cipher;
        this.http = http;
    }

    // Returns the provider URL to open (in a popup). connectionId: reconnect that connection.
    // oauthClientId: sign in through that OAuth app of the user's; null uses the server's app.
    public String start(String userId, String appId, String connectionId, String oauthClientId) {
        if (!cipher.isConfigured()) {
            throw new ConnectionsNotConfiguredException();
        }
        Connector connector = registry.find(appId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown app: " + appId));
        if (connector.oauthProvider() == null) {
            throw new IllegalArgumentException(connector.name() + " doesn't support signing in with OAuth");
        }
        Provider provider = providers.find(connector.oauthProvider())
                .orElseThrow(() -> new IllegalArgumentException(connector.name() + " doesn't support signing in with OAuth"));
        String clientId;
        if (oauthClientId != null) {
            OAuthClient client = clients.owned(userId, oauthClientId);
            if (!client.getProvider().equals(provider.id())) {
                throw new IllegalArgumentException("\"" + client.getName() + "\" is not a " + provider.displayName() + " OAuth app");
            }
            clientId = client.getClientId();
        } else if (provider.configured()) {
            clientId = provider.clientId();
        } else {
            throw new IllegalArgumentException(connector.name()
                    + " sign-in with the server's app isn't set up. Use your own OAuth app.");
        }
        if (connectionId != null && !connections.owned(userId, connectionId).getAppId().equals(appId)) {
            throw new IllegalArgumentException("That connection belongs to a different app");
        }

        long now = System.currentTimeMillis();
        states.deleteOlderThan(now - STATE_TTL_MS);
        String state = randomToken(32);
        String verifier = randomToken(48);
        states.save(new OAuthState(state, userId, appId, provider.id(), connectionId, oauthClientId,
                cipher.encrypt(Map.of("v", verifier)), now));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", providers.callbackUrl(provider.id()));
        if (provider.scopes() != null && !provider.scopes().isBlank()) {
            params.put("scope", provider.scopes());
        }
        params.put("state", state);
        if (provider.pkce()) {
            params.put("code_challenge", challenge(verifier));
            params.put("code_challenge_method", "S256");
        }
        params.putAll(provider.extraAuthorizeParams());
        return provider.authorizeUrl() + "?" + query(params);
    }

    public record Result(boolean success, String connectionId, String appId, String message) {

        static Result failed(String appId, String message) {
            return new Result(false, null, appId, message);
        }
    }

    // Handles the provider's redirect. Never throws: the browser always gets sent back to the
    // UI with either the new connection id or a message it can show.
    public Result complete(String code, String state, String error, String errorDescription) {
        if (state == null || state.isBlank()) {
            return Result.failed(null, "The sign-in response was missing its state. Start again.");
        }
        OAuthState s = states.findById(state).orElse(null);
        // consume() deletes the row; only one callback can get 1 back, so a replayed or
        // duplicated callback is rejected even if both arrive at once.
        if (s == null || states.consume(state) == 0) {
            return Result.failed(null, "This sign-in has expired or was already used. Start again.");
        }
        if (s.getCreatedAt() < System.currentTimeMillis() - STATE_TTL_MS) {
            return Result.failed(s.getAppId(), "This sign-in took too long and expired. Start again.");
        }
        Provider provider = providers.find(s.getProvider()).orElse(null);
        if (provider == null) {
            return Result.failed(s.getAppId(), "This OAuth provider is no longer supported.");
        }
        if (error != null) {
            return Result.failed(s.getAppId(), "access_denied".equals(error)
                    ? "Sign-in was cancelled."
                    : explain(provider, error, errorDescription, s.getOauthClientId() != null));
        }
        if (code == null || code.isBlank()) {
            return Result.failed(s.getAppId(), "The provider didn't send an authorization code. Start again.");
        }

        try {
            ClientCredentials client = clients.credentialsFor(provider, s.getOauthClientId());
            Map<String, String> params = new LinkedHashMap<>();
            params.put("grant_type", "authorization_code");
            params.put("code", code);
            params.put("redirect_uri", providers.callbackUrl(provider.id()));
            if (provider.pkce()) {
                params.put("code_verifier", cipher.decrypt(s.getCodeVerifier()).get("v"));
            }
            TokenResponse response = http.token(provider, client, params);

            Object access = response.body().get(TokenService.ACCESS_TOKEN);
            if (response.error() != null || response.status() >= 400 || access == null) {
                return Result.failed(s.getAppId(), response.error() != null
                        ? explain(provider, response.error(), null, s.getOauthClientId() != null)
                        : provider.displayName() + " didn't complete the sign-in (HTTP " + response.status() + "). Start again.");
            }

            Map<String, String> tokens = new LinkedHashMap<>();
            tokens.put(TokenService.ACCESS_TOKEN, String.valueOf(access));
            Object refresh = response.body().get(TokenService.REFRESH_TOKEN);
            if (refresh != null) {
                tokens.put(TokenService.REFRESH_TOKEN, String.valueOf(refresh));
            }
            long now = System.currentTimeMillis();
            Object scope = response.body().get("scope");
            String label = accountLabel(s.getAppId(), provider, String.valueOf(access));

            ConnectionService.ConnectionView saved = connections.saveOAuth(s.getUserId(), s.getAppId(),
                    s.getConnectionId(), s.getOauthClientId(), label, tokens, scope == null ? null : String.valueOf(scope),
                    TokenService.expiresAt(response.body(), now));
            return new Result(true, saved.id(), s.getAppId(), null);
        } catch (RuntimeException e) {
            // Includes ConnectionVerificationException (provider unreachable, identity check failed).
            return Result.failed(s.getAppId(), e.getMessage() != null ? e.getMessage() : "Sign-in failed. Start again.");
        }
    }

    // Provider errors in words the user can act on. With their own OAuth app, most failures are
    // a mistyped client id/secret or a callback URL that doesn't match ours.
    private String explain(Provider provider, String error, String description, boolean ownApp) {
        String name = provider.displayName();
        if (isClientCredentialsError(error)) {
            return ownApp
                    ? name + " rejected your OAuth app's client ID or secret (" + error + "). Check them and try again."
                    : name + " rejected this server's OAuth app (" + error + "). Ask the administrator to check it.";
        }
        if ("redirect_uri_mismatch".equals(error) || "bad_redirect_uri".equals(error)) {
            return "The callback URL registered in the " + name + " OAuth app doesn't match "
                    + providers.callbackUrl(provider.id()) + ". Update it there and try again.";
        }
        return name + " didn't complete the sign-in (" + (description != null ? description : error) + "). Start again.";
    }

    // GitHub says incorrect_client_credentials; RFC 6749 providers say invalid_client; Slack says
    // invalid_client_id or bad_client_secret.
    static boolean isClientCredentialsError(String error) {
        return "invalid_client".equals(error) || "incorrect_client_credentials".equals(error)
                || "unauthorized_client".equals(error) || "invalid_client_id".equals(error)
                || "bad_client_secret".equals(error);
    }

    // Names the account with the same "who am I" check the app's token form uses, passing the
    // OAuth access token as that token (e.g. GitHub /user -> "@octocat").
    private String accountLabel(String appId, Provider provider, String accessToken) {
        Connector connector = registry.find(appId).orElse(null);
        if (connector != null && connector.token() != null) {
            String key = connector.token().fields().stream()
                    .filter(CredentialField::secret).map(CredentialField::key).findFirst().orElse("token");
            return connector.token().verifier().verify(Map.of(key, accessToken));
        }
        return provider.displayName() + " account";
    }

    private String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String query(Map<String, String> params) {
        StringBuilder q = new StringBuilder();
        params.forEach((k, v) -> {
            if (!q.isEmpty()) q.append('&');
            q.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return q.toString();
    }
}
