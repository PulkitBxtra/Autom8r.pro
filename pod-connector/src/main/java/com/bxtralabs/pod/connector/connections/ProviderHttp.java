package com.bxtralabs.pod.connector.connections;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

// Talks to app providers (checking tokens now, OAuth exchanges later). Every failure becomes a
// ConnectionVerificationException with a user-facing message that names the provider but never
// echoes the credential or the request URL (which can carry one, e.g. Trello's ?token=).
@Component
public class ProviderHttp {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final JsonMapper jsonMapper;

    public ProviderHttp(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public Map<String, Object> get(String provider, String url, Map<String, String> headers) {
        return send(provider, request(url, headers).GET().build());
    }

    // Empty-body POST (e.g. Slack's auth.test), or a form body when given.
    public Map<String, Object> post(String provider, String url, Map<String, String> headers, String formBody) {
        HttpRequest.Builder builder = request(url, headers);
        if (formBody != null) {
            builder.header("Content-Type", "application/x-www-form-urlencoded");
        }
        return send(provider, builder.POST(formBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(formBody)).build());
    }

    // Status + parsed body of an OAuth token endpoint call. Token endpoints report problems like
    // invalid_grant in the body (with 400, or GitHub-style with 200), so callers inspect it
    // themselves instead of getting a generic "rejected" message.
    public record TokenResponse(int status, Map<String, Object> body) {

        public String error() {
            Object e = body.get("error");
            return e == null ? null : String.valueOf(e);
        }
    }

    // application/x-www-form-urlencoded POST. Only network failures throw.
    public TokenResponse postForm(String provider, String url, Map<String, String> form) {
        StringBuilder encoded = new StringBuilder();
        form.forEach((k, v) -> {
            if (v == null) return;
            if (!encoded.isEmpty()) encoded.append('&');
            encoded.append(java.net.URLEncoder.encode(k, java.nio.charset.StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8));
        });
        HttpRequest request = request(url, Map.of("Content-Type", "application/x-www-form-urlencoded"))
                .POST(HttpRequest.BodyPublishers.ofString(encoded.toString())).build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ConnectionVerificationException("Couldn't reach " + provider + " ("
                    + e.getClass().getSimpleName() + "). Try again in a moment.");
        }
        Map<String, Object> body;
        try {
            String raw = response.body();
            body = raw == null || raw.isBlank() ? Map.of()
                    : jsonMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            body = Map.of("error", "non_json_response");
        }
        return new TokenResponse(response.statusCode(), body);
    }

    private HttpRequest.Builder request(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "Autom8r");
        headers.forEach(builder::setHeader);
        return builder;
    }

    private Map<String, Object> send(String provider, HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Only the exception type: some messages include the URL.
            throw new ConnectionVerificationException("Couldn't reach " + provider + " to check the connection ("
                    + e.getClass().getSimpleName() + "). Try again in a moment.");
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new ConnectionVerificationException(provider + " rejected these credentials (HTTP " + status
                    + "). Check they're correct, not expired, and have the needed permissions.");
        }
        if (status >= 400) {
            throw new ConnectionVerificationException(provider + " returned HTTP " + status + " while checking the connection");
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ConnectionVerificationException(provider + " sent a response that wasn't JSON");
        }
    }

    // Reads a nested value as text: string(json, "bot", "workspace_name"). Null if any step is missing.
    @SuppressWarnings("unchecked")
    public static String string(Map<String, Object> json, String... path) {
        Object current = json;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current == null ? null : String.valueOf(current);
    }
}
