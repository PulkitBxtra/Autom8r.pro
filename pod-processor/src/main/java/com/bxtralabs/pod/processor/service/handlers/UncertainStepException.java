package com.bxtralabs.pod.processor.service.handlers;

// The call may have reached the app and done its work, but no answer came back (a timeout after
// sending, a dropped connection, a 5xx on a create). Retried like any temporary failure, but the
// next attempt is told (StepContext.mayHaveHappened) so it checks before doing it again.
public class UncertainStepException extends Exception {

    public UncertainStepException(String message) {
        super(message);
    }
}
