package com.bxtralabs.pod.connector.connections;

import java.util.List;
import java.util.Map;

// How one app authenticates. An app can offer a token form (token != null), OAuth
// (oauthProvider != null, built in A8), or both. appId matches the app catalog ids.
// oauthScopes: what this app's sign-in asks for, when it differs from its provider's (Gmail and
// Sheets share Google's sign-in but each asks only for its own access); null = the provider's.
// oauthAccount: names the account from an OAuth access token (passed as "token") for apps without
// a token form; null = the token form's verifier, if any.
public record Connector(String appId, String name, String description, TokenAuth token, String oauthProvider,
                        String oauthScopes, TokenVerifier oauthAccount) {

    public Connector(String appId, String name, String description, TokenAuth token, String oauthProvider) {
        this(appId, name, description, token, oauthProvider, null, null);
    }

    // A credential the user types in. secret fields are masked in the UI and never shown again.
    public record CredentialField(String key, String label, boolean secret, boolean required,
                                  String placeholder, String help) {
    }

    // verifier checks the credentials with the provider and returns the connection's label
    // (the account/workspace name), or throws ConnectionVerificationException.
    public record TokenAuth(List<CredentialField> fields, String docsUrl, TokenVerifier verifier) {
    }

    @FunctionalInterface
    public interface TokenVerifier {
        String verify(Map<String, String> credentials);
    }
}
