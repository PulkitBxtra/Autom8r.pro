package com.bxtralabs.pod.connector.common;

// The request is valid but clashes with current state (e.g. deleting something still in use) -> 409.
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
