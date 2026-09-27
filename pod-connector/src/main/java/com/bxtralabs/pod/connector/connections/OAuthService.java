package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.connections.Connector.CredentialField;
import com.bxtralabs.pod.connector.connections.OAuthProviders.Provider;
import com.bxtralabs.pod.connector.connections.ProviderHttp.TokenResponse;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.model.OAuthState;
import com.bxtralabs.pod.connector.repository.OAuthStateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

// "Connect with <provider>": authorization code flow with PKCE.
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
    private final CredentialCipher cipher;
    private final ProviderHttp http;
    private final String callbackUrl;
    private final SecureRandom random = new SecureRandom();

    public OAuthService(ConnectorRegistry registry, OAuthProviders providers, OAuthStateRepository states,
                        ConnectionService connections, CredentialCipher cipher, ProviderHttp http,
                        @Value("${app.public-url:http://localhost:8084}") String publicUrl) {
        this.registry = registry;
        this.providers = providers;
        this.states = states;
        this.connections = connections;
        this.cipher = cipher;
        this.http = http;
        // Must match the callback URL registered with the provider exactly.
        this.callbackUrl = publicUrl.replaceAll("/+$", "") + "/oauth/callback";
    }

    public String callbackUrl() {
        return callbackUrl;
    }

    // Returns the provider URL to open (in a popup). connectionId: reconnect that connection.
    public String start(String userId, String appId, String connectionId) {
        if (!cipher.isConfigured()) {
            throw new ConnectionsNotConfiguredException();
        }
        Connector connector = registry.find(appId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown app: " + appId));
        if (connector.oauthProvider() == null) {
            throw new IllegalArgumentException(connector.name() + " doesn't support signing in with OAuth");
        }
        Provider provider = providers.find(connector.oauthProvider()).filter(Provider::configured)
                .orElseThrow(() -> new IllegalArgumentException(connector.name() + " sign-in isn't set up on this server yet"));
        if (connectionId != null && !connections.owned(userId, connectionId).getAppId().equals(appId)) {
            throw new IllegalArgumentException("That connection belongs to a different app");
        }

        long now = System.currentTimeMillis();
        states.deleteOlderThan(now - STATE_TTL_MS);
        String state = randomToken(32);
        String verifier = randomToken(48);
        states.save(new OAuthState(state, userId, appId, provider.id(), connectionId,
                cipher.encrypt(Map.of("v", verifier)), now));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", provider.clientId());
        params.put("redirect_uri", callbackUrl);
        params.put("scope", provider.scopes());
        params.put("state", state);
        params.put("code_challenge", challenge(verifier));
        params.put("code_challenge_method", "S256");
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
        if (error != null) {
            return Result.failed(s.getAppId(), "access_denied".equals(error)
                    ? "Sign-in was cancelled."
                    : "The provider reported an error: " + (errorDescription != null ? errorDescription : error));
        }
        if (code == null || code.isBlank()) {
            return Result.failed(s.getAppId(), "The provider didn't send an authorization code. Start again.");
        }

        try {
            Provider provider = providers.find(s.getProvider()).filter(Provider::configured)
                    .orElseThrow(() -> new IllegalStateException("OAuth provider is no longer configured"));
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", code);
            form.put("redirect_uri", callbackUrl);
            form.put("client_id", provider.clientId());
            form.put("client_secret", provider.clientSecret());
            form.put("code_verifier", cipher.decrypt(s.getCodeVerifier()).get("v"));
            TokenResponse response = http.postForm(provider.displayName(), provider.tokenUrl(), form);

            Object access = response.body().get(TokenService.ACCESS_TOKEN);
            if (response.error() != null || response.status() >= 400 || access == null) {
                return Result.failed(s.getAppId(), provider.displayName() + " didn't complete the sign-in ("
                        + (response.error() != null ? response.error() : "HTTP " + response.status()) + "). Start again.");
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
                    s.getConnectionId(), label, tokens, scope == null ? null : String.valueOf(scope),
                    TokenService.expiresAt(response.body(), now));
            return new Result(true, saved.id(), s.getAppId(), null);
        } catch (RuntimeException e) {
            // Includes ConnectionVerificationException (provider unreachable, identity check failed).
            return Result.failed(s.getAppId(), e.getMessage() != null ? e.getMessage() : "Sign-in failed. Start again.");
        }
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
