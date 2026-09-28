package com.bxtralabs.pod.backend.common;

// The request is fine but clashes with the current state (e.g. saving over a newer version); 409.
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
