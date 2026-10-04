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
    //   JSON        JSON body carrying client_id and client_secret (Atlassian, for Trello)
    public enum TokenStyle { FORM, BASIC_JSON, JSON }

    // setupUrl: where a user creates their own OAuth app with this provider.
    // pkce: whether it supports PKCE (code_challenge / code_verifier); Notion doesn't.
    // workspaceResource: for providers whose sign-in is for one workspace the user names (Trello
    // Power-Ups): the prefix of the "resource" sent with it, followed by the workspace ID.
    public record Provider(String id, String displayName, String authorizeUrl, String tokenUrl, String scopes,
                           String clientId, String clientSecret, Map<String, String> extraAuthorizeParams,
                           String setupUrl, TokenStyle tokenStyle, boolean pkce, String workspaceResource) {

        public Provider(String id, String displayName, String authorizeUrl, String tokenUrl, String scopes,
                        String clientId, String clientSecret, Map<String, String> extraAuthorizeParams, String setupUrl) {
            this(id, displayName, authorizeUrl, tokenUrl, scopes, clientId, clientSecret, extraAuthorizeParams, setupUrl,
                    TokenStyle.FORM, true);
        }

        public Provider(String id, String displayName, String authorizeUrl, String tokenUrl, String scopes,
                        String clientId, String clientSecret, Map<String, String> extraAuthorizeParams, String setupUrl,
                        TokenStyle tokenStyle, boolean pkce) {
            this(id, displayName, authorizeUrl, tokenUrl, scopes, clientId, clientSecret, extraAuthorizeParams, setupUrl,
                    tokenStyle, pkce, null);
        }

        public boolean needsWorkspace() {
            return workspaceResource != null;
        }

        public boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final String callbackUrl;
    // Providers whose callback is somewhere else than <public-url>/oauth/callback (see Slack).
    private final Map<String, String> callbackOverrides = new LinkedHashMap<>();

    // Without Notion's, Slack's or Trello's server apps (tests that only care about GitHub/Google).
    public OAuthProviders(String githubOauthBase, String githubClientId, String githubClientSecret, String githubScopes,
                          String googleClientId, String googleClientSecret, String publicUrl) {
        this(githubOauthBase, githubClientId, githubClientSecret, githubScopes, googleClientId, googleClientSecret,
                "https://api.notion.com/v1", "", "", "https://slack.com", "", "", "", "", publicUrl);
    }

    // Without Trello's server app (tests from before Trello sign-in).
    public OAuthProviders(String githubOauthBase, String githubClientId, String githubClientSecret, String githubScopes,
                          String googleClientId, String googleClientSecret, String notionOauthBase, String notionClientId,
                          String notionClientSecret, String slackOauthBase, String slackClientId, String slackClientSecret,
                          String slackScopes, String slackCallbackUrl, String publicUrl) {
        this(githubOauthBase, githubClientId, githubClientSecret, githubScopes, googleClientId, googleClientSecret,
                notionOauthBase, notionClientId, notionClientSecret, slackOauthBase, slackClientId, slackClientSecret,
                slackScopes, slackCallbackUrl, "https://auth.atlassian.com", "", "", publicUrl);
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
                          @Value("${connectors.slack.oauth-base:https://slack.com}") String slackOauthBase,
                          @Value("${connectors.slack.oauth.client-id:}") String slackClientId,
                          @Value("${connectors.slack.oauth.client-secret:}") String slackClientSecret,
                          @Value("${connectors.slack.oauth.scopes:}") String slackScopes,
                          @Value("${connectors.slack.oauth.callback-url:}") String slackCallbackUrl,
                          @Value("${connectors.trello.oauth-base:https://auth.atlassian.com}") String trelloOauthBase,
                          @Value("${connectors.trello.oauth.client-id:}") String trelloClientId,
                          @Value("${connectors.trello.oauth.client-secret:}") String trelloClientSecret,
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
        // A Slack app installed as a bot: comma-separated bot scopes, token from oauth.v2.access
        // (which answers 200 with ok=false and an error on failure). Bot tokens don't expire
        // unless the app turns on token rotation, so there's nothing to refresh.
        String slack = slackOauthBase.replaceAll("/+$", "");
        register(new Provider("slack", "Slack",
                slack + "/oauth/v2/authorize",
                slack + "/api/oauth.v2.access",
                slackScopes == null || slackScopes.isBlank() ? SLACK_SCOPES : slackScopes,
                slackClientId, slackClientSecret, Map.of(),
                "https://api.slack.com/apps",
                TokenStyle.FORM, false));
        // Trello through Atlassian's OAuth 2.0 (a Power-Up's OAuth 2.0 tab, confidential client):
        // PKCE, prompt=consent, JSON token requests. A Power-Up's sign-in is for one workspace,
        // which the user names (resource=ari:cloud:trello::workspace/<id>; Atlassian refuses the
        // sign-in without it). Access tokens last an hour; offline_access gets a refresh token
        // (90 days), which Atlassian rotates on every refresh.
        String atlassian = trelloOauthBase.replaceAll("/+$", "");
        register(new Provider("trello", "Trello",
                atlassian + "/authorize",
                atlassian + "/oauth/token",
                TRELLO_SCOPES, trelloClientId, trelloClientSecret,
                Map.of("prompt", "consent"),
                "https://trello.com/power-ups/admin",
                TokenStyle.JSON, true, "ari:cloud:trello::workspace/"));
        // Slack only accepts https callbacks, so locally (http://localhost) its sign-in comes back
        // through a tunnel to this pod instead: https://<tunnel>/oauth/callback.
        if (slackCallbackUrl != null && !slackCallbackUrl.isBlank()) {
            callbackOverrides.put("slack", slackCallbackUrl.trim());
        }
    }

    // What Slack steps and triggers need: post and DM, find channels and users, and receive
    // channel messages and mentions.
    public static final String SLACK_SCOPES =
            "chat:write,channels:read,channels:history,app_mentions:read,im:write,users:read";

    // What Trello steps need: name the account, read boards and lists, create and move cards.
    public static final String TRELLO_SCOPES = "read:member:trello read:board:trello write:board:trello offline_access";

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

    // Where this provider sends the browser back after sign-in.
    public String callbackUrl(String providerId) {
        return callbackOverrides.getOrDefault(providerId, callbackUrl);
    }
}
