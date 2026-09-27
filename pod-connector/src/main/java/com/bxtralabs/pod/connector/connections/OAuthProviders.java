package com.bxtralabs.pod.connector.connections;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

// OAuth providers and their client credentials. A provider is only offered in the UI once its
// client id and secret are configured (from the provider's developer console, e.g. GitHub ->
// Settings -> Developer settings -> OAuth Apps, with callback URL <public-url>/oauth/callback).
// Base URLs are configurable so tests can point at a local mock provider.
@Component
public class OAuthProviders {

    public record Provider(String id, String displayName, String authorizeUrl, String tokenUrl, String scopes,
                           String clientId, String clientSecret, Map<String, String> extraAuthorizeParams) {

        public boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    private final Map<String, Provider> providers = new LinkedHashMap<>();

    public OAuthProviders(@Value("${connectors.github.oauth-base:https://github.com}") String githubOauthBase,
                          @Value("${connectors.github.oauth.client-id:}") String githubClientId,
                          @Value("${connectors.github.oauth.client-secret:}") String githubClientSecret,
                          @Value("${connectors.github.oauth.scopes:read:user repo}") String githubScopes,
                          @Value("${connectors.google.oauth.client-id:}") String googleClientId,
                          @Value("${connectors.google.oauth.client-secret:}") String googleClientSecret) {
        register(new Provider("github", "GitHub",
                githubOauthBase + "/login/oauth/authorize",
                githubOauthBase + "/login/oauth/access_token",
                githubScopes, githubClientId, githubClientSecret, Map.of()));
        // Declared so Gmail/Sheets light up once configured. access_type=offline + prompt=consent
        // make Google return a refresh token.
        register(new Provider("google", "Google",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "openid email https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/spreadsheets",
                googleClientId, googleClientSecret,
                Map.of("access_type", "offline", "prompt", "consent")));
    }

    private void register(Provider provider) {
        providers.put(provider.id(), provider);
    }

    public Optional<Provider> find(String id) {
        return Optional.ofNullable(id == null ? null : providers.get(id));
    }

    public boolean isAvailable(String id) {
        return find(id).map(Provider::configured).orElse(false);
    }
}
