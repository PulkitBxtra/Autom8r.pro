package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.common.NotFoundException;
import com.bxtralabs.pod.connector.connections.OAuthClientService.ClientCredentials;
import com.bxtralabs.pod.connector.connections.OAuthProviders.Provider;
import com.bxtralabs.pod.connector.connections.ProviderHttp.TokenResponse;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

// Hands out usable credentials for a connection, refreshing OAuth access tokens as needed.
// Two ways in, both under the connection's row lock so they can never refresh at the same time:
//  - getValidCredentials: on demand (a step about to call the app). Refreshes if the token
//    expires within `refresh-margin-ms`, so it's correct even if the scheduled job missed.
//  - refreshLocked: from TokenRefreshScheduler, which keeps tokens fresh ahead of time.
@Service
public class TokenService {

    public static final String ACCESS_TOKEN = "access_token";
    public static final String REFRESH_TOKEN = "refresh_token";

    private final ConnectionRepository repository;
    private final ConnectorRegistry registry;
    private final OAuthProviders providers;
    private final OAuthClientService clients;
    private final CredentialCipher cipher;
    private final ProviderHttp http;
    private final long refreshMarginMs;

    public TokenService(ConnectionRepository repository, ConnectorRegistry registry, OAuthProviders providers,
                        OAuthClientService clients, CredentialCipher cipher, ProviderHttp http,
                        @Value("${connections.refresh-margin-ms:60000}") long refreshMarginMs) {
        this.repository = repository;
        this.registry = registry;
        this.providers = providers;
        this.clients = clients;
        this.cipher = cipher;
        this.http = http;
        this.refreshMarginMs = refreshMarginMs;
    }

    // noRollbackFor: these are thrown *after* recording NEEDS_REAUTH or the backoff counters,
    // which must be committed, not rolled back along with the exception.
    @Transactional(noRollbackFor = {ConnectionNeedsReauthException.class, TokenRefreshException.class})
    public Map<String, String> getValidCredentials(String connectionId) {
        // Waits here if the scheduler (or another request) is refreshing this connection, then
        // sees its result -- which is why the expiry is checked only after taking the lock.
        Connection c = repository.findByIdForUpdate(connectionId)
                .orElseThrow(() -> new NotFoundException("Connection not found: " + connectionId));
        if (Connection.STATUS_NEEDS_REAUTH.equals(c.getStatus())) {
            throw new ConnectionNeedsReauthException(appName(c) + " needs to be reconnected"
                    + (c.getLastError() != null ? ": " + c.getLastError() : ""));
        }

        Map<String, String> credentials = cipher.decrypt(c.getCredentials());
        long now = System.currentTimeMillis();
        if (Connection.AUTH_OAUTH.equals(c.getAuthType()) && c.getExpiresAt() != null
                && c.getExpiresAt() - now < refreshMarginMs) {
            credentials = refreshLocked(c, credentials, now);
        }
        c.setLastUsedAt(now);
        repository.save(c);
        return credentials;
    }

    // Refreshes a connection the caller already holds the row lock for (same transaction), and
    // returns the credentials to use. On a temporary failure it keeps the old token if that's
    // still valid, and backs off; on invalid_grant it marks the connection NEEDS_REAUTH.
    // Uses the same OAuth app (the server's or the user's own) that issued the tokens.
    Map<String, String> refreshLocked(Connection c, Map<String, String> credentials, long now) {
        Provider provider = providerFor(c);
        boolean ownApp = c.getOauthClientId() != null;
        ClientCredentials client;
        try {
            client = clients.credentialsFor(provider, c.getOauthClientId());
        } catch (IllegalStateException e) {
            if (ownApp) {
                markNeedsReauth(c, e.getMessage() + ". Reconnect it.");
                throw new ConnectionNeedsReauthException(appName(c) + " needs to be reconnected");
            }
            throw new TokenRefreshException(e.getMessage());
        }
        String refreshToken = credentials.get(REFRESH_TOKEN);
        if (refreshToken == null || refreshToken.isBlank()) {
            markNeedsReauth(c, "The provider didn't issue a refresh token, so the expired access token can't be renewed");
            throw new ConnectionNeedsReauthException(appName(c) + " needs to be reconnected");
        }

        TokenResponse response;
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", refreshToken);
            form.put("client_id", client.clientId());
            form.put("client_secret", client.clientSecret());
            response = http.postForm(provider.displayName(), provider.tokenUrl(), form);
        } catch (ConnectionVerificationException networkError) {
            return temporaryFailure(c, credentials, networkError.getMessage(), now);
        }

        String error = response.error();
        if ("invalid_grant".equals(error)) {
            // Revoked by the user, or the refresh token expired. Retrying can't fix this.
            markNeedsReauth(c, provider.displayName() + " no longer accepts this connection (invalid_grant). Reconnect it.");
            throw new ConnectionNeedsReauthException(appName(c) + " needs to be reconnected");
        }
        if (ownApp && OAuthService.isClientCredentialsError(error)) {
            // The user deleted their OAuth app or rotated its secret without updating it here.
            // Only they can fix that. (For the server's app it's an operator mistake that fixing
            // the config resolves for everyone, so that stays a temporary failure below.)
            markNeedsReauth(c, provider.displayName() + " rejected your OAuth app's client ID or secret ("
                    + error + "). Update the app's secret, then reconnect.");
            throw new ConnectionNeedsReauthException(appName(c) + " needs to be reconnected");
        }
        Object access = response.body().get(ACCESS_TOKEN);
        if (error != null || response.status() >= 400 || access == null) {
            return temporaryFailure(c, credentials,
                    provider.displayName() + " refresh failed (HTTP " + response.status()
                            + (error != null ? ", " + error : "") + ")", now);
        }

        Map<String, String> fresh = new LinkedHashMap<>(credentials);
        fresh.put(ACCESS_TOKEN, String.valueOf(access));
        // Providers that rotate refresh tokens invalidate the old one right now, so the new one
        // must be saved in this same transaction. Others don't send one: keep the old.
        Object rotated = response.body().get(REFRESH_TOKEN);
        if (rotated != null) {
            fresh.put(REFRESH_TOKEN, String.valueOf(rotated));
        }
        c.setCredentials(cipher.encrypt(fresh));
        c.setExpiresAt(expiresAt(response.body(), now));
        c.setLastRefreshedAt(now);
        c.setRefreshFailures(0);
        c.setNextRefreshAt(null);
        c.setLastError(null);
        repository.save(c);
        return fresh;
    }

    // Keeps using the current token if it hasn't actually expired yet; backs off the next try
    // 1, 2, 4 ... up to 60 minutes so a struggling provider isn't hammered every minute.
    private Map<String, String> temporaryFailure(Connection c, Map<String, String> credentials, String reason, long now) {
        int failures = c.getRefreshFailures() + 1;
        c.setRefreshFailures(failures);
        c.setNextRefreshAt(now + Math.min(60L, 1L << Math.min(failures - 1, 6)) * 60_000L);
        c.setLastError(reason);
        repository.save(c);
        if (c.getExpiresAt() != null && c.getExpiresAt() > now) {
            return credentials;
        }
        throw new TokenRefreshException(reason);
    }

    private void markNeedsReauth(Connection c, String reason) {
        c.setStatus(Connection.STATUS_NEEDS_REAUTH);
        c.setLastError(reason);
        c.setNextRefreshAt(null);
        repository.save(c);
    }

    // expires_in is seconds from now; absent means the token doesn't expire (e.g. GitHub OAuth Apps).
    static Long expiresAt(Map<String, Object> body, long now) {
        Object expiresIn = body.get("expires_in");
        if (expiresIn == null) {
            return null;
        }
        try {
            return now + Long.parseLong(String.valueOf(expiresIn).trim()) * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Provider providerFor(Connection c) {
        String providerId = registry.find(c.getAppId()).map(Connector::oauthProvider).orElse(null);
        return providers.find(providerId)
                .orElseThrow(() -> new TokenRefreshException("OAuth isn't supported for " + appName(c)));
    }

    private String appName(Connection c) {
        return registry.find(c.getAppId()).map(Connector::name).orElse(c.getAppId());
    }
}
