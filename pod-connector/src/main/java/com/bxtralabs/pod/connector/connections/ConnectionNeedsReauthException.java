package com.bxtralabs.pod.connector.connections;

// The provider no longer accepts this connection (revoked, refresh token expired...). Retrying
// won't help; the user has to reconnect it on the Connections page.
public class ConnectionNeedsReauthException extends RuntimeException {

    public ConnectionNeedsReauthException(String message) {
        super(message);
    }
}
