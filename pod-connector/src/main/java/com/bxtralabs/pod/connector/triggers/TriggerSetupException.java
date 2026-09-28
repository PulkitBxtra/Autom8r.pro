package com.bxtralabs.pod.connector.triggers;

// Registering a trigger with the app failed, for a reason worth showing the user
// (e.g. "GitHub didn't allow creating a webhook on octo/app: the account needs admin access").
public class TriggerSetupException extends Exception {

    public TriggerSetupException(String message) {
        super(message);
    }
}
