package com.bxtralabs.pod.processor.service.handlers;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Optional;
import java.util.regex.Pattern;

// type "http_request": calls a URL.
// input: url (required), method (default GET), headers (map), body (string, or any JSON value
// which is sent as JSON), timeoutSeconds (default 30, max 120).
// output: status, body (parsed as JSON when it is JSON, otherwise text).
// A 4xx/5xx response fails the step. 5xx, 408, 429, timeouts and connection errors are
// temporary (the step is retried); other 4xx and a missing/invalid url are permanent.
//
// The URL is whatever the workflow author configured, so every URL, and every redirect hop,
// goes through UrlGuard: only http(s) to public addresses (http.allow-private-addresses turns
// that off for local development). Redirects are followed here, at most 5; on one to another
// origin, credential-looking headers (Authorization, Cookie, X-Api-Key...) are dropped.
@Component
@Order(1)
public class HttpRequestHandler implements ActionHandler {

    public static final String TYPE = "http_request";
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int ERROR_BODY_PREVIEW = 300;
    static final int MAX_REDIRECTS = 5;
    private static final Pattern SENSITIVE_HEADER =
            Pattern.compile("auth|cookie|token|secret|passw|api[-_]?key|private|session|signature", Pattern.CASE_INSENSITIVE);

    // Redirects are followed by hand, so each hop is checked.
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final JsonMapper jsonMapper;
    private final UrlGuard guard;

    @Autowired
    public HttpRequestHandler(JsonMapper jsonMapper,
                              @Value("${http.allow-private-addresses:false}") boolean allowPrivateAddresses) {
        this(jsonMapper, new UrlGuard(UrlGuard.DNS, allowPrivateAddresses));
    }

    HttpRequestHandler(JsonMapper jsonMapper, UrlGuard guard) {
        this.jsonMapper = jsonMapper;
        this.guard = guard;
    }

    @Override
    public boolean supports(GraphNode node) {
        return TYPE.equals(node.type());
    }

    // Requests that change something carry Idempotency-Key: the step run's id, the same on every
    // retry, so APIs that support the header (Stripe, many others) apply it only once. A key set
    // in the step's own headers wins; GET/HEAD don't need one. The recorded input is unchanged.
    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input, StepCredentials credentials,
                                       StepContext context) throws Exception {
        String method = input.get("method") == null ? "GET" : String.valueOf(input.get("method")).toUpperCase();
        if (context == null || method.equals("GET") || method.equals("HEAD")
                || (input.get("headers") instanceof Map<?, ?> own && hasHeader(own, "idempotency-key"))) {
            return execute(node, input, credentials);
        }
        Map<String, Object> headers = new LinkedHashMap<>();
        if (input.get("headers") instanceof Map<?, ?> own) {
            own.forEach((k, v) -> headers.put(String.valueOf(k), v));
        }
        headers.put("Idempotency-Key", context.stepRunId());
        Map<String, Object> withKey = new LinkedHashMap<>(input);
        withKey.put("headers", headers);
        return execute(node, withKey, credentials);
    }

    // With an HTTP connection: its header (e.g. Authorization) is added to the request, unless the
    // step's own settings set the same header, which then wins. The recorded step input doesn't
    // change, so the credential never shows up in the run.
    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input, StepCredentials credentials)
            throws Exception {
        String name = credentials == null ? null : credentials.get("headerName");
        String value = credentials == null ? null : credentials.get("headerValue");
        if (name == null || name.isBlank() || value == null) {
            return execute(node, input);
        }
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(name.trim(), value);
        if (input.get("headers") instanceof Map<?, ?> own) {
            own.forEach((k, v) -> {
                headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(String.valueOf(k)));
                headers.put(String.valueOf(k), v);
            });
        }
        Map<String, Object> withHeader = new LinkedHashMap<>(input);
        withHeader.put("headers", headers);
        return execute(node, withHeader);
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input) throws Exception {
        Object url = input.get("url");
        if (url == null || String.valueOf(url).isBlank()) {
            throw new PermanentStepException("http_request needs a url");
        }
        String method = input.get("method") == null ? "GET" : String.valueOf(input.get("method")).toUpperCase();
        int timeout = Math.min(asInt(input.get("timeoutSeconds"), 30), MAX_TIMEOUT_SECONDS);

        URI uri;
        try {
            uri = URI.create(String.valueOf(url).trim());
        } catch (IllegalArgumentException badUrl) {
            throw new PermanentStepException("http_request has an invalid url: " + url);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (input.get("headers") instanceof Map<?, ?> given) {
            given.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
        }
        Object body = input.get("body");
        String bodyText = null;
        if (body instanceof String s) {
            bodyText = s;
        } else if (body != null) {
            bodyText = jsonMapper.writeValueAsString(body);
            if (!hasHeader(headers, "content-type")) {
                headers.put("Content-Type", "application/json");
            }
        }

        HttpResponse<String> response;
        for (int hop = 0; ; hop++) {
            guard.check(uri);
            HttpRequest.Builder request;
            try {
                request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout));
                headers.forEach(request::header);
            } catch (IllegalArgumentException bad) {
                throw new PermanentStepException("http_request can't send this request: " + bad.getMessage());
            }
            request.method(method, bodyText == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(bodyText));
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            Optional<String> location = response.headers().firstValue("location");
            if (!isRedirect(status) || location.isEmpty()) {
                break;
            }
            if (hop >= MAX_REDIRECTS) {
                throw new PermanentStepException("More than " + MAX_REDIRECTS + " redirects from " + method + " " + url);
            }
            URI next;
            try {
                next = uri.resolve(location.get().trim());
            } catch (IllegalArgumentException bad) {
                throw new PermanentStepException(url + " redirected to an invalid URL: " + location.get());
            }
            // Like browsers: 303, and 301/302 after anything but GET/HEAD, continue as a GET.
            if (status == 303 || ((status == 301 || status == 302) && !method.equals("GET") && !method.equals("HEAD"))) {
                method = "GET";
                bodyText = null;
                headers.keySet().removeIf(h -> h.equalsIgnoreCase("content-type"));
            }
            if (!sameOrigin(uri, next)) {
                headers.keySet().removeIf(h -> SENSITIVE_HEADER.matcher(h).find());
            }
            uri = next;
        }

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

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return String.valueOf(a.getScheme()).equalsIgnoreCase(String.valueOf(b.getScheme()))
                && String.valueOf(a.getHost()).equalsIgnoreCase(String.valueOf(b.getHost()))
                && port(a) == port(b);
    }

    private static int port(URI u) {
        return u.getPort() != -1 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
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
