package com.bxtralabs.pod.backend.triggers;

import com.bxtralabs.pod.backend.model.graph.GraphNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

// Tells pod-connector which workflows' app triggers to listen for (it registers them with the
// apps, e.g. a GitHub repository webhook, and turns the apps' events into runs).
@Component
public class TriggerSync {

    // What pod-connector reports: ACTIVE (listening) or ERROR with a message for the user.
    public record Status(String status, String error) {
        public boolean listening() {
            return "ACTIVE".equals(status);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper jsonMapper;
    private final String baseUrl;
    private final String internalToken;

    public TriggerSync(JsonMapper jsonMapper,
                       @Value("${connector.base-url:http://localhost:8084}") String baseUrl,
                       @Value("${internal.api-token:}") String internalToken) {
        this.jsonMapper = jsonMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    // Registers (or re-registers) the trigger. null: the app has no app triggers to register.
    public Status subscribe(String workflowId, String userId, GraphNode trigger) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("appId", trigger.appId());
        body.put("triggerId", trigger.itemId());
        body.put("connectionId", trigger.connectionId());
        body.put("config", trigger.parameters() == null ? Map.of() : trigger.parameters());
        HttpResponse<String> response = send("PUT", workflowId, body);
        if (response.statusCode() == 204) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Couldn't set up the trigger right now (connections service answered HTTP "
                    + response.statusCode() + "). Try again.");
        }
        Map<?, ?> status = jsonMapper.readValue(response.body(), Map.class);
        return new Status(String.valueOf(status.get("status")), status.get("error") == null ? null : String.valueOf(status.get("error")));
    }

    public void unsubscribe(String workflowId) {
        HttpResponse<String> response = send("DELETE", workflowId, null);
        if (response.statusCode() != 204) {
            throw new IllegalStateException("Couldn't turn off the trigger right now (connections service answered HTTP "
                    + response.statusCode() + "). Try again.");
        }
    }

    private HttpResponse<String> send(String method, String workflowId, Object body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/triggers/" + workflowId))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Couldn't reach the connections service to set up the trigger. Try again.");
        }
    }
}
