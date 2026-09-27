package com.bxtralabs.pod.processor.service.handlers;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

// type "http_request": calls a URL.
// input: url (required), method (default GET), headers (map), body (string, or any JSON value
// which is sent as JSON), timeoutSeconds (default 30, max 120).
// output: status, body (parsed as JSON when it is JSON, otherwise text).
// A 4xx/5xx response fails the step. 5xx, 408, 429, timeouts and connection errors are
// temporary (the step is retried); other 4xx and a missing/invalid url are permanent.
//
// Note: the URL is whatever the workflow author configured, so this can reach anything the
// processor can reach, including internal services. Block private/loopback addresses before
// this is exposed to untrusted users.
@Component
@Order(1)
public class HttpRequestHandler implements ActionHandler {

    public static final String TYPE = "http_request";
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int ERROR_BODY_PREVIEW = 300;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final JsonMapper jsonMapper;

    public HttpRequestHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean supports(GraphNode node) {
        return TYPE.equals(node.type());
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input) throws Exception {
        Object url = input.get("url");
        if (url == null || String.valueOf(url).isBlank()) {
            throw new PermanentStepException("http_request needs a url");
        }
        String method = input.get("method") == null ? "GET" : String.valueOf(input.get("method")).toUpperCase();
        int timeout = Math.min(asInt(input.get("timeoutSeconds"), 30), MAX_TIMEOUT_SECONDS);

        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(String.valueOf(url))).timeout(Duration.ofSeconds(timeout));
        } catch (IllegalArgumentException badUrl) {
            throw new PermanentStepException("http_request has an invalid url: " + url);
        }

        if (input.get("headers") instanceof Map<?, ?> headers) {
            headers.forEach((k, v) -> request.header(String.valueOf(k), String.valueOf(v)));
        }

        Object body = input.get("body");
        HttpRequest.BodyPublisher publisher;
        if (body == null) {
            publisher = HttpRequest.BodyPublishers.noBody();
        } else if (body instanceof String s) {
            publisher = HttpRequest.BodyPublishers.ofString(s);
        } else {
            publisher = HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body));
            if (!(input.get("headers") instanceof Map<?, ?> h) || !hasHeader(h, "content-type")) {
                request.header("Content-Type", "application/json");
            }
        }
        request.method(method, publisher);

        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            String preview = response.body() == null ? "" : response.body();
            if (preview.length() > ERROR_BODY_PREVIEW) {
                preview = preview.substring(0, ERROR_BODY_PREVIEW) + "...";
            }
            String message = "HTTP " + response.statusCode() + " from " + method + " " + url
                    + (preview.isBlank() ? "" : ": " + preview);
            if (isTemporary(response.statusCode())) {
                throw new IllegalStateException(message);
            }
            throw new PermanentStepException(message);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", response.statusCode());
        output.put("body", parseBody(response.body()));
        return output;
    }

    // Server errors, request timeout and rate limiting may succeed later; other 4xx won't.
    static boolean isTemporary(int status) {
        return status >= 500 || status == 408 || status == 429;
    }

    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(body, Object.class);
        } catch (Exception notJson) {
            return body;
        }
    }

    private static boolean hasHeader(Map<?, ?> headers, String name) {
        return headers.keySet().stream().anyMatch(k -> String.valueOf(k).equalsIgnoreCase(name));
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
