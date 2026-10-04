package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.model.TriggerSubscription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

// GitHub triggers as repository webhooks:
//   trg_github_new_issue  "issues" events, action "opened"
//   trg_github_new_pr     "pull_request" events, action "opened"
// Creating a webhook needs admin access to the repository, plus: for an OAuth App connection the
// admin:repo_hook scope; for a GitHub App connection (ghu_ tokens, where scopes don't apply) the
// app's "Webhooks: Read and write" repository permission with the app installed on the repository;
// for a fine-grained token, "Webhooks: read and write".
// Also turns a delivery into the trigger's data (toTriggerBody), or null for events it ignores.
@Component
public class GitHubTriggers implements AppTriggerRegistrar {

    public static final String NEW_ISSUE = "trg_github_new_issue";
    public static final String NEW_PR = "trg_github_new_pr";
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final String apiBase;

    public GitHubTriggers(JsonMapper jsonMapper, @Value("${connectors.github.api-base:https://api.github.com}") String apiBase) {
        this.jsonMapper = jsonMapper;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override
    public boolean supports(String appId) {
        return "app_github".equals(appId);
    }

    static String event(String triggerId) {
        return NEW_PR.equals(triggerId) ? "pull_request" : "issues";
    }

    @Override
    public Registration register(TriggerSubscription s, Map<String, String> credentials, String hookUrl, String secret)
            throws TriggerSetupException {
        String repository = repository(s);
        Map<String, Object> body = Map.of("name", "web", "active", true, "events", List.of(event(s.getTriggerId())),
                "config", Map.of("url", hookUrl, "content_type", "json", "secret", secret, "insecure_ssl", "0"));
        HttpResponse<String> response = call(credentials, "POST", "/repos/" + repository + "/hooks", body);
        int status = response.statusCode();
        if (status == 201) {
            return new Registration(String.valueOf(jsonMapper.readValue(response.body(), Map.class).get("id")), null);
        }
        String message = messageOf(response.body());
        throw new TriggerSetupException(switch (status) {
            case 401 -> "GitHub no longer accepts this account's token. Reconnect it, then turn the workflow on again.";
            case 403, 404 -> "GitHub didn't let this account add a webhook to " + repository + " (" + message
                    + "). It needs admin access to the repository. Connected through a GitHub App: give the app the"
                    + " \"Webhooks: Read and write\" repository permission and install it on " + repository
                    + ". Through an OAuth App: reconnect to grant the admin:repo_hook permission.";
            case 422 -> "GitHub rejected the webhook for " + repository + ": " + message;
            default -> "GitHub couldn't add the webhook to " + repository + " (HTTP " + status + "). Try again.";
        });
    }

    @Override
    public void unregister(TriggerSubscription s, Map<String, String> credentials) {
        if (s.getExternalId() == null) return;
        try {
            call(credentials, "DELETE", "/repos/" + repository(s) + "/hooks/" + s.getExternalId(), null);
        } catch (Exception e) {
            // Already gone, or GitHub unreachable: a leftover hook's deliveries are rejected anyway
            // (no subscription matches), and the user can delete it in the repo's settings.
            System.out.println("Couldn't remove GitHub hook " + s.getExternalId() + ": " + e.getMessage());
        }
    }

    // The trigger's data for a delivery, or empty when the event isn't one this trigger starts on.
    public Optional<Map<String, Object>> toTriggerBody(String triggerId, String event, Map<?, ?> payload) {
        if (!event(triggerId).equals(event) || !"opened".equals(payload.get("action"))) {
            return Optional.empty();
        }
        Map<?, ?> item = (Map<?, ?>) payload.get(NEW_PR.equals(triggerId) ? "pull_request" : "issue");
        Map<?, ?> repo = (Map<?, ?>) payload.get("repository");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repository", repo == null ? null : repo.get("full_name"));
        out.put("number", item.get("number"));
        out.put("title", item.get("title"));
        out.put("body", item.get("body"));
        out.put("url", item.get("html_url"));
        out.put("author", item.get("user") instanceof Map<?, ?> u ? u.get("login") : null);
        if (item.get("labels") instanceof List<?> labels) {
            out.put("labels", labels.stream().map(l -> l instanceof Map<?, ?> m ? m.get("name") : l).toList());
        }
        if (NEW_PR.equals(triggerId)) {
            out.put("branch", item.get("head") instanceof Map<?, ?> h ? h.get("ref") : null);
            out.put("baseBranch", item.get("base") instanceof Map<?, ?> b ? b.get("ref") : null);
            out.put("draft", item.get("draft"));
        }
        out.put("createdAt", item.get("created_at"));
        return Optional.of(out);
    }

    private static String repository(TriggerSubscription s) throws TriggerSetupException {
        Object r = s.getConfig() == null ? null : s.getConfig().get("repository");
        String repository = r == null ? "" : String.valueOf(r).trim();
        if (!REPOSITORY.matcher(repository).matches()) {
            throw new TriggerSetupException("Repository must look like owner/repo, got \"" + repository + "\"");
        }
        return repository;
    }

    private HttpResponse<String> call(Map<String, String> credentials, String method, String path, Object body)
            throws TriggerSetupException {
        String token = credentials.get("access_token") != null ? credentials.get("access_token") : credentials.get("token");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "Autom8r")
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new TriggerSetupException("Couldn't reach GitHub to set up the trigger. Try again.");
        }
    }

    private String messageOf(String body) {
        try {
            Map<?, ?> m = jsonMapper.readValue(body, Map.class);
            Object msg = m.get("message");
            if (m.get("errors") instanceof List<?> errors && !errors.isEmpty() && errors.getFirst() instanceof Map<?, ?> first
                    && first.get("message") != null) {
                return msg + ": " + first.get("message");
            }
            return String.valueOf(msg);
        } catch (RuntimeException notJson) {
            return "HTTP error";
        }
    }
}
