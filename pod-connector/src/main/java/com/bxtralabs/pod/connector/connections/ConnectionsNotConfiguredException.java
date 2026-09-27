package com.bxtralabs.pod.connector.connections;

// Connections need an encryption key to store credentials; without one they're switched off
// (GlobalExceptionHandler -> 503) instead of the pod refusing to start.
public class ConnectionsNotConfiguredException extends RuntimeException {

    public ConnectionsNotConfiguredException() {
        super("Connections aren't configured on this server (CONNECTIONS_ENCRYPTION_KEY is not set)");
    }
}
