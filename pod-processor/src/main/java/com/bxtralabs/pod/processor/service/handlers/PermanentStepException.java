package com.bxtralabs.pod.processor.service.handlers;

// Thrown by a handler when retrying can't help (bad config, 4xx from an API): the step fails
// immediately. Any other exception is treated as temporary and the step is retried.
public class PermanentStepException extends Exception {

    public PermanentStepException(String message) {
        super(message);
    }
}
