package com.bxtralabs.pod.processor.service.handlers;

import java.util.Map;

// What a step's connection gives its handler to call the app: an OAuth access token
// ("access_token"), a token connection's fields ("token", "apiKey"...), or an HTTP connection's
// header ("headerName", "headerValue"). Handed to the handler only; never stored on the step
// run, logged, or put in an error message, which is why toString leaves the values out.
// version: which stored credentials these are (from pod-connector), named when reporting that the
// app rejected them, so a report about since-replaced credentials is ignored.
public record StepCredentials(String connectionId, String appId, String authType, Map<String, String> values,
                              String version) {

    public StepCredentials(String connectionId, String appId, String authType, Map<String, String> values) {
        this(connectionId, appId, authType, values, null);
    }

    public String get(String key) {
        return values == null ? null : values.get(key);
    }

    @Override
    public String toString() {
        return "StepCredentials[" + connectionId + ", " + appId + ", " + authType + ", keys="
                + (values == null ? "[]" : values.keySet()) + "]";
    }
}
