package com.bxtralabs.pod.processor.service.handlers;

// The app refused the step's account (a 401, a revoked or invalid token). Fails the step for good,
// and the connection is reported to pod-connector as needing reconnecting, so the Connections
// page shows it and later steps using it stop before calling the app.
public class AccountRejectedException extends PermanentStepException {

    public AccountRejectedException(String message) {
        super(message);
    }
}
