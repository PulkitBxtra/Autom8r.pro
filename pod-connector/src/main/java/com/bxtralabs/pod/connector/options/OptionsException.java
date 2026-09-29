package com.bxtralabs.pod.connector.options;

// The app couldn't give a list: 422 when the account lacks a permission (the user can fix it by
// reconnecting), 502 when the app failed or couldn't be reached.
public class OptionsException extends RuntimeException {

    private final int status;

    public OptionsException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
