package com.bxtralabs.pod.connector.triggers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

// Starts a run of a workflow in pod-webhooks (which records it for the engine), with the app
// event's data as the trigger's body. Throws if the run couldn't be started, so the app's
// delivery fails and the app retries it.
@Component
public class RunStarter {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper jsonMapper;
    private final String baseUrl;
    private final String internalToken;

    public RunStarter(JsonMapper jsonMapper,
                      @Value("${webhooks.base-url:http://localhost:8080}") String baseUrl,
                      @Value("${internal.api-token:}") String internalToken) {
        this.jsonMapper = jsonMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public String start(String workflowId, Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/workflows/" + workflowId + "/runs"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(Map.of("body", body))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("pod-webhooks answered HTTP " + response.statusCode());
            }
            return String.valueOf(jsonMapper.readValue(response.body(), Map.class).get("runId"));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Couldn't start a run of " + workflowId + ": " + e.getMessage(), e);
        }
    }
}
