package com.bxtralabs.pod.processor.service.handlers.code;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

// Runs a Code step's script through pod-sandbox (POST {url}/run), never in this process: a
// script must not reach the processor's environment (DB password, INTERNAL_API_TOKEN), its
// memory, or other users' runs. pod-sandbox starts each script in a fresh JVM under its own user
// id, in a container with no network (see pod-sandbox's ProcessRunner).
@Component
public class CodeSandbox {

    // On top of the script's timeout: starting its JVM and compiling, plus waiting for a turn.
    static final Duration EXTRA_WAIT = Duration.ofSeconds(20);

    // What the runner answered (see pod-sandbox's ScriptRunner).
    //   kind: script, blocked, timeout, memory, result (on failure)
    public record Result(boolean ok, Object result, String logs, String error, String kind, Integer line) {
    }

    // pod-sandbox couldn't be reached, was busy, or its runner didn't start: not the script's
    // fault, so the step is retried.
    public static class SandboxUnavailableException extends Exception {
        public SandboxUnavailableException(String message) {
            super(message);
        }
    }

    private final JsonMapper jsonMapper;
    private final URI runUri;
    private final String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    public CodeSandbox(JsonMapper jsonMapper,
                       @Value("${code.sandbox.url:http://localhost:8090}") String url,
                       @Value("${code.sandbox.token:}") String token) {
        this.jsonMapper = jsonMapper;
        this.runUri = URI.create(url.replaceAll("/+$", "") + "/run");
        this.token = token;
    }

    public Result run(String script, Map<String, Object> bindings, int timeoutSeconds) throws SandboxUnavailableException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("script", script);
        request.put("bindings", bindings);
        request.put("timeoutSeconds", timeoutSeconds);
        HttpRequest.Builder b = HttpRequest.newBuilder(runUri)
                .timeout(Duration.ofSeconds(timeoutSeconds).plus(EXTRA_WAIT))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(request)));
        if (token != null && !token.isBlank()) {
            b.header("X-Sandbox-Token", token);
        }

        HttpResponse<String> response;
        try {
            response = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new SandboxUnavailableException("The code runner didn't answer in time");
        } catch (IOException e) {
            throw new SandboxUnavailableException("The code runner can't be reached at " + runUri + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxUnavailableException("Interrupted while the script ran");
        }

        Map<?, ?> answer;
        try {
            answer = jsonMapper.readValue(response.body(), Map.class);
        } catch (RuntimeException notJson) {
            throw new SandboxUnavailableException("The code runner answered " + response.statusCode() + " with something that isn't JSON");
        }
        if (response.statusCode() != 200) {
            // 503 busy or couldn't start; 401 a token mismatch (a setup problem, but retrying
            // keeps the run until it's fixed rather than failing every Code step).
            throw new SandboxUnavailableException("The code runner answered " + response.statusCode() + ": " + answer.get("error"));
        }
        return new Result(Boolean.TRUE.equals(answer.get("ok")), answer.get("result"),
                answer.get("logs") == null ? "" : String.valueOf(answer.get("logs")),
                answer.get("error") == null ? null : String.valueOf(answer.get("error")),
                answer.get("kind") == null ? null : String.valueOf(answer.get("kind")),
                answer.get("line") instanceof Number n ? n.intValue() : null);
    }
}
