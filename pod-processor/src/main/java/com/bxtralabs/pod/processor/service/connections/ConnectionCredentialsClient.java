package com.bxtralabs.pod.processor.service.connections;

import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import com.bxtralabs.pod.processor.service.handlers.StepCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

// Fetches a step's connection credentials from pod-connector just before the step runs (the
// only pod holding the encryption key; it refreshes OAuth tokens as needed). Nothing is cached:
// each attempt gets a currently valid token.
// A connection that's gone or needs reconnecting fails the step for good with a message the user
// can act on; anything else (pod-connector down, provider refresh failing) is retried.
@Component
public class ConnectionCredentialsClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper jsonMapper;
    private final String baseUrl;
    private final String internalToken;

    public ConnectionCredentialsClient(JsonMapper jsonMapper,
                                       @Value("${connector.base-url:http://localhost:8084}") String baseUrl,
                                       @Value("${internal.api-token:}") String internalToken) {
        this.jsonMapper = jsonMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public StepCredentials fetch(String connectionId, String userId, String appId) throws PermanentStepException {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/connections/" + connectionId + "/credentials"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(Map.of("userId", userId, "appId", appId))))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't reach the connections service to get this step's account: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while getting this step's account");
        }

        int status = response.statusCode();
        if (status == 200) {
            Map<?, ?> body = jsonMapper.readValue(response.body(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, String> values = (Map<String, String>) body.get("credentials");
            Object version = body.get("version");
            return new StepCredentials(connectionId, appId, String.valueOf(body.get("authType")), values,
                    version == null ? null : String.valueOf(version));
        }
        String error = errorOf(response.body());
        switch (status) {
            case 404 -> throw new PermanentStepException(
                    "The account chosen for this step no longer exists. Choose another in the step's settings.");
            case 409 -> throw new PermanentStepException(error + ". Reconnect it on the Connections page, then run again.");
            case 401 -> throw new IllegalStateException(
                    "The connections service refused this pod (check INTERNAL_API_TOKEN is the same on both)");
            default -> throw new IllegalStateException("Couldn't get this step's account right now"
                    + (error.isBlank() ? " (HTTP " + status + ")" : ": " + error));
        }
    }

    // Tells pod-connector the app rejected these credentials, so the connection shows as needing
    // reconnecting. Best effort: the step fails either way. Returns whether it was marked.
    public boolean reportRejected(StepCredentials credentials, String userId, String reason) {
        if (credentials == null || credentials.version() == null) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/connections/"
                            + credentials.connectionId() + "/rejected"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(Map.of(
                            "userId", userId, "appId", credentials.appId(), "version", credentials.version(), "reason", reason))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && Boolean.TRUE.equals(jsonMapper.readValue(response.body(), Map.class).get("marked"));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.out.println("Couldn't report rejected credentials for " + credentials.connectionId() + ": " + e.getClass().getSimpleName());
            return false;
        }
    }

    private String errorOf(String body) {
        try {
            Object error = jsonMapper.readValue(body, Map.class).get("error");
            return error == null ? "" : String.valueOf(error);
        } catch (RuntimeException notJson) {
            return "";
        }
    }
}
