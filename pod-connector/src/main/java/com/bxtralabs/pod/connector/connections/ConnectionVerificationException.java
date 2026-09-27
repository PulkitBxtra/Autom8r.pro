package com.bxtralabs.pod.connector.connections;

// The provider rejected the credentials, or couldn't be reached to check them. The message is
// shown to the user as-is (400), so it must never contain the credential itself.
public class ConnectionVerificationException extends IllegalArgumentException {

    public ConnectionVerificationException(String message) {
        super(message);
    }
}
