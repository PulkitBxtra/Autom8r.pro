package com.bxtralabs.pod.processor.service.handlers.github;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.ActionHandler;
import com.bxtralabs.pod.processor.service.handlers.AppCalls;
import com.bxtralabs.pod.processor.service.handlers.StepContext;
import com.bxtralabs.pod.processor.service.handlers.UncertainStepException;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import com.bxtralabs.pod.processor.service.handlers.StepCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

// GitHub actions, through the step's GitHub connection (an OAuth access token, or a personal
// access token for a token connection):
//   github.create_issue    repository "owner/repo", title, body?, labels? ("bug, triage")
//                          -> {number, url, id, title, state, labels}
//   github.create_comment  repository, issueNumber, body -> {id, url, issueNumber}
// Not twice: issues and comments carry a hidden marker naming the step run; if an earlier attempt
// may have created one (StepContext.mayHaveHappened), recent issues/comments are checked for the
// marker first and a match is returned (alreadyDone: true) instead of creating another.
// Failures: 401 (token no longer accepted), 403 without a rate limit (no access), 404 (no such
// repository or issue, or the account can't see it), 410 (issues turned off) and 422 (GitHub
// rejected the input) fail the step for good; rate limits, 5xx and network errors are retried.
@Component
@Order(1)
public class GitHubHandler implements ActionHandler {

    public static final String CREATE_ISSUE = "github.create_issue";
    public static final String CREATE_COMMENT = "github.create_comment";
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final JsonMapper jsonMapper;
    private final String apiBase;

    public GitHubHandler(JsonMapper jsonMapper, @Value("${connectors.github.api-base:https://api.github.com}") String apiBase) {
        this.jsonMapper = jsonMapper;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override
    public boolean supports(GraphNode node) {
        return CREATE_ISSUE.equals(node.type()) || CREATE_COMMENT.equals(node.type());
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input) throws Exception {
        return execute(node, input, null);
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input, StepCredentials credentials) throws Exception {
        return execute(node, input, credentials, null);
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input, StepCredentials credentials,
                                       StepContext context) throws Exception {
        String token = credentials == null ? null
                : Optional.ofNullable(credentials.get("access_token")).orElse(credentials.get("token"));
        if (token == null || token.isBlank()) {
            throw new PermanentStepException("This step needs a GitHub account. Choose one in the step's setup.");
        }
        String repository = text(input.get("repository"));
        if (!REPOSITORY.matcher(repository).matches()) {
            throw new PermanentStepException("Repository must look like owner/repo, got \"" + repository + "\"");
        }
        String repoPath = "/repos/" + repository.split("/")[0] + "/" + enc(repository.split("/")[1]);

        if (CREATE_ISSUE.equals(node.type())) {
            List<String> labels = labels(input.get("labels"));
            Map<?, ?> issue = context != null && context.mayHaveHappened()
                    ? find(token, repoPath + "/issues?state=all&sort=created&direction=desc&per_page=50&since="
                    + AppCalls.since(context.firstStartedAt()), context)
                    : null;
            boolean alreadyDone = issue != null;
            if (issue == null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("title", text(input.get("title")));
                String issueBody = withMarker(text(input.get("body")), context);
                if (!issueBody.isBlank()) body.put("body", issueBody);
                if (!labels.isEmpty()) body.put("labels", labels);
                issue = post(token, repoPath + "/issues", body, "repository " + repository);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("number", issue.get("number"));
            out.put("url", issue.get("html_url"));
            out.put("id", issue.get("id"));
            out.put("title", issue.get("title"));
            out.put("state", issue.get("state"));
            out.put("labels", labels);
            if (alreadyDone) out.put("alreadyDone", true);
            return out;
        }

        Object number = input.get("issueNumber");
        long issueNumber;
        try {
            issueNumber = number instanceof Number n ? n.longValue() : Long.parseLong(text(number));
        } catch (NumberFormatException e) {
            throw new PermanentStepException("Issue or PR number must be a number, got \"" + text(number) + "\"");
        }
        String commentsPath = repoPath + "/issues/" + issueNumber + "/comments";
        Map<?, ?> comment = context != null && context.mayHaveHappened()
                ? find(token, commentsPath + "?per_page=100&since=" + AppCalls.since(context.firstStartedAt()), context)
                : null;
        boolean alreadyDone = comment != null;
        if (comment == null) {
            comment = post(token, commentsPath, Map.of("body", withMarker(text(input.get("body")), context)),
                    "issue #" + issueNumber + " in " + repository);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", comment.get("id"));
        out.put("url", comment.get("html_url"));
        out.put("issueNumber", issueNumber);
        if (alreadyDone) out.put("alreadyDone", true);
        return out;
    }

    // An HTML comment: invisible on GitHub, but lets a later attempt recognise this issue/comment.
    private static String withMarker(String body, StepContext context) {
        if (context == null) return body;
        String marker = "<!-- " + context.marker() + " -->";
        return body.isBlank() ? marker : body + "\n\n" + marker;
    }

    // The issue or comment an earlier attempt of this step created, if any.
    private Map<?, ?> find(String token, String path, StepContext context) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Autom8r")
                .GET().build();
        HttpResponse<String> response = AppCalls.send(http, request, "GitHub", false);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Couldn't check GitHub for an earlier attempt's result (HTTP "
                    + response.statusCode() + "); will try again");
        }
        String marker = context.marker();
        for (Object item : jsonMapper.readValue(response.body(), List.class)) {
            if (item instanceof Map<?, ?> m && String.valueOf(m.get("body")).contains(marker)) {
                return m;
            }
        }
        return null;
    }

    private Map<?, ?> post(String token, String path, Map<String, Object> body, String what) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Autom8r")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = AppCalls.send(http, request, "GitHub", true);
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return jsonMapper.readValue(response.body(), Map.class);
        }
        String message = messageOf(response.body());
        boolean rateLimited = status == 429 || (status == 403 && ("0".equals(response.headers().firstValue("x-ratelimit-remaining").orElse(null))
                || message.toLowerCase(Locale.ROOT).contains("rate limit")));
        if (rateLimited) {
            throw new IllegalStateException("GitHub rate limit reached; will try again");
        }
        if (status >= 500) {
            throw AppCalls.uncertainServerError("GitHub", status, what);
        }
        throw new PermanentStepException(switch (status) {
            case 401 -> "GitHub no longer accepts this account's token. Reconnect it on the Connections page.";
            case 403 -> "The GitHub account can't do this on " + what + ": " + message;
            case 404 -> "GitHub couldn't find " + what + ", or the account can't see it";
            case 410 -> "Issues are turned off for " + what;
            case 422 -> "GitHub rejected the request: " + message;
            default -> "GitHub answered HTTP " + status + ": " + message;
        });
    }

    // "Validation Failed (Label: invalid)" from GitHub's {"message", "errors": [...]}.
    private String messageOf(String body) {
        try {
            Map<?, ?> m = jsonMapper.readValue(body, Map.class);
            String message = String.valueOf(m.get("message"));
            if (m.get("errors") instanceof List<?> errors && !errors.isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (Object e : errors) {
                    if (e instanceof Map<?, ?> err) {
                        parts.add(err.get("message") != null ? String.valueOf(err.get("message"))
                                : err.get("resource") + " " + err.get("field") + " " + err.get("code"));
                    }
                }
                message += " (" + String.join("; ", parts) + ")";
            }
            return message;
        } catch (RuntimeException notJson) {
            return body == null ? "" : body.substring(0, Math.min(200, body.length()));
        }
    }

    // "bug, triage" or ["bug", "triage"] -> ["bug", "triage"]
    private static List<String> labels(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Collection<?> list) {
            list.forEach(v -> addLabel(out, text(v)));
        } else {
            for (String part : text(value).split(",")) addLabel(out, part);
        }
        return out;
    }

    private static void addLabel(List<String> out, String label) {
        String l = label.trim();
        if (!l.isEmpty() && !out.contains(l)) out.add(l);
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
