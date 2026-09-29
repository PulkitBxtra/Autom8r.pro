package com.bxtralabs.pod.connector.connections;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OAuthProvidersTest {

    private static OAuthProviders providers(String slackClientId, String slackCallbackUrl) {
        return new OAuthProviders("https://github.com", "gh-id", "gh-secret", "repo", "", "",
                "https://api.notion.com/v1", "", "", "https://slack.com/", slackClientId, "slack-secret", "", slackCallbackUrl,
                "http://localhost:8084/");
    }

    @Test
    void slackSignsInAsABotWithCommaSeparatedScopes() {
        OAuthProviders.Provider slack = providers("slack-id", "").find("slack").orElseThrow();
        assertEquals("https://slack.com/oauth/v2/authorize", slack.authorizeUrl());
        assertEquals("https://slack.com/api/oauth.v2.access", slack.tokenUrl());
        assertEquals(OAuthProviders.TokenStyle.FORM, slack.tokenStyle());
        assertFalse(slack.pkce());
        assertTrue(slack.scopes().contains("chat:write,") && !slack.scopes().contains(" "), slack.scopes());
        assertTrue(slack.scopes().contains("app_mentions:read") && slack.scopes().contains("channels:history"));
        assertTrue(slack.configured());
        assertFalse(providers("", "").isAvailable("slack"));
    }

    @Test
    void slacksCallbackCanGoThroughATunnelWhileTheOthersStayLocal() {
        OAuthProviders tunnelled = providers("slack-id", "https://abc.trycloudflare.com/oauth/callback");
        assertEquals("https://abc.trycloudflare.com/oauth/callback", tunnelled.callbackUrl("slack"));
        assertEquals("http://localhost:8084/oauth/callback", tunnelled.callbackUrl("github"));
        assertEquals("http://localhost:8084/oauth/callback", tunnelled.callbackUrl("notion"));
        assertEquals("http://localhost:8084/oauth/callback", providers("slack-id", "").callbackUrl("slack"));
    }
}
