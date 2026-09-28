package com.bxtralabs.pod.connector.connections;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

// OAuth providers, and the server's own client credentials for each. Users can always sign in
// with their own OAuth app (OAuthClient); the server's app is offered too once its client id and
// secret are configured (from the provider's developer console, e.g. GitHub -> Settings ->
// Developer settings, with callback URL <public-url>/oauth/callback). Base URLs are
// configurable so tests can point at a local mock provider.
@Component
public class OAuthProviders {

    // How a provider's token endpoint wants to be called (code exchange and refresh alike):
    //   FORM        form body carrying client_id and client_secret (GitHub, Google, RFC 6749)
    //   BASIC_JSON  client id:secret as HTTP Basic auth, JSON body without them (Notion)
    public enum TokenStyle { FORM, BASIC_JSON }

    // setupUrl: where a user creates their own OAuth app with this provider.
    // pkce: whether it supports PKCE (code_challenge / code_verifier); Notion doesn't.
    public record Provider(String id, String displayName, String authorizeUrl, String tokenUrl, String scopes,
                           String clientId, String clientSecret, Map<String, String> extraAuthorizeParams,
                           String setupUrl, TokenStyle tokenStyle, boolean pkce) {

        public Provider(String id, String displayName, String authorizeUrl, String tokenUrl, String scopes,
                        String clientId, String clientSecret, Map<String, String> extraAuthorizeParams, String setupUrl) {
            this(id, displayName, authorizeUrl, tokenUrl, scopes, clientId, clientSecret, extraAuthorizeParams, setupUrl,
                    TokenStyle.FORM, true);
        }

        public boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final String callbackUrl;

    // Without Notion's server app (tests that only care about GitHub/Google).
    public OAuthProviders(String githubOauthBase, String githubClientId, String githubClientSecret, String githubScopes,
                          String googleClientId, String googleClientSecret, String publicUrl) {
        this(githubOauthBase, githubClientId, githubClientSecret, githubScopes, googleClientId, googleClientSecret,
                "https://api.notion.com/v1", "", "", publicUrl);
    }

    @Autowired
    public OAuthProviders(@Value("${connectors.github.oauth-base:https://github.com}") String githubOauthBase,
                          @Value("${connectors.github.oauth.client-id:}") String githubClientId,
                          @Value("${connectors.github.oauth.client-secret:}") String githubClientSecret,
                          @Value("${connectors.github.oauth.scopes:read:user repo}") String githubScopes,
                          @Value("${connectors.google.oauth.client-id:}") String googleClientId,
                          @Value("${connectors.google.oauth.client-secret:}") String googleClientSecret,
                          @Value("${connectors.notion.oauth-base:https://api.notion.com/v1}") String notionOauthBase,
                          @Value("${connectors.notion.oauth.client-id:}") String notionClientId,
                          @Value("${connectors.notion.oauth.client-secret:}") String notionClientSecret,
                          @Value("${app.public-url:http://localhost:8084}") String publicUrl) {
        // Every provider sends the browser back here, for the server's app and users' own apps
        // alike (the state says which sign-in it is). Must match what's registered exactly.
        this.callbackUrl = publicUrl.replaceAll("/+$", "") + "/oauth/callback";
        register(new Provider("github", "GitHub",
                githubOauthBase + "/login/oauth/authorize",
                githubOauthBase + "/login/oauth/access_token",
                githubScopes, githubClientId, githubClientSecret, Map.of(),
                "https://github.com/settings/developers"));
        // Declared so Gmail/Sheets light up once configured. access_type=offline + prompt=consent
        // make Google return a refresh token.
        register(new Provider("google", "Google",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "openid email https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/spreadsheets",
                googleClientId, googleClientSecret,
                Map.of("access_type", "offline", "prompt", "consent"),
                "https://console.cloud.google.com/apis/credentials"));
        // A Notion "public connection". No scopes (access is whatever pages the user picks when
        // signing in), owner=user, Basic-auth JSON token requests, no PKCE. Refreshing rotates
        // both tokens.
        String notion = notionOauthBase.replaceAll("/+$", "");
        register(new Provider("notion", "Notion",
                notion + "/oauth/authorize",
                notion + "/oauth/token",
                "", notionClientId, notionClientSecret,
                Map.of("owner", "user"),
                "https://www.notion.so/profile/integrations",
                TokenStyle.BASIC_JSON, false));
    }

    private void register(Provider provider) {
        providers.put(provider.id(), provider);
    }

    public Optional<Provider> find(String id) {
        return Optional.ofNullable(id == null ? null : providers.get(id));
    }

    // The server's own app for this provider is configured.
    public boolean isAvailable(String id) {
        return find(id).map(Provider::configured).orElse(false);
    }

    public String callbackUrl() {
        return callbackUrl;
    }
}
