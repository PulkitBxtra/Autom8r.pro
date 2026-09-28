package com.bxtralabs.pod.processor.service.handlers.slack;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.ActionHandler;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// Slack actions, through the step's Slack connection (a bot token):
//   slack.post_message  channel ("#general" or an ID), text, threadTs? -> {channel, ts, text}
//   slack.send_dm       user (an ID like U012AB3CD, or an email), text -> {channel, ts, user}
// Slack answers HTTP 200 with {"ok": false, "error": "..."} for most problems. The ones only the
// user can fix (no such channel, bot not in it, missing scope, revoked token...) fail the step
// for good with a readable reason; rate limits (429), 5xx and Slack's own internal errors are
// retried.
@Component
@Order(1)
public class SlackHandler implements ActionHandler {

    public static final String POST_MESSAGE = "slack.post_message";
    public static final String SEND_DM = "slack.send_dm";
    private static final Set<String> TEMPORARY = Set.of("ratelimited", "internal_error", "fatal_error",
            "request_timeout", "service_unavailable");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final String apiBase;

    public SlackHandler(JsonMapper jsonMapper, @Value("${connectors.slack.api-base:https://slack.com/api}") String apiBase) {
        this.jsonMapper = jsonMapper;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override
    public boolean supports(GraphNode node) {
        return POST_MESSAGE.equals(node.type()) || SEND_DM.equals(node.type());
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input) throws Exception {
        return execute(node, input, null);
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input, StepCredentials credentials) throws Exception {
        String token = credentials == null ? null : credentials.get("access_token") != null
                ? credentials.get("access_token") : credentials.get("token");
        if (token == null || token.isBlank()) {
            throw new PermanentStepException("This step needs a Slack account. Choose one in the step's setup.");
        }
        String text = text(input.get("text"));

        if (POST_MESSAGE.equals(node.type())) {
            String channel = text(input.get("channel"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("channel", channel.startsWith("#") ? channel.substring(1) : channel);
            body.put("text", text);
            if (!text(input.get("threadTs")).isBlank()) body.put("thread_ts", text(input.get("threadTs")));
            Map<?, ?> res = call(token, "chat.postMessage", body, "channel " + channel);
            return Map.of("channel", String.valueOf(res.get("channel")), "ts", String.valueOf(res.get("ts")), "text", text);
        }

        String user = text(input.get("user"));
        String userId = user;
        if (user.contains("@")) {
            Map<?, ?> found = get(token, "users.lookupByEmail?email=" + URLEncoder.encode(user, StandardCharsets.UTF_8), "user " + user);
            userId = String.valueOf(((Map<?, ?>) found.get("user")).get("id"));
        }
        Map<?, ?> opened = call(token, "conversations.open", Map.of("users", userId), "user " + user);
        String channel = String.valueOf(((Map<?, ?>) opened.get("channel")).get("id"));
        Map<?, ?> res = call(token, "chat.postMessage", Map.of("channel", channel, "text", text), "user " + user);
        return Map.of("channel", channel, "ts", String.valueOf(res.get("ts")), "user", userId);
    }

    private Map<?, ?> call(String token, String method, Map<String, Object> body, String what) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(apiBase + "/" + method))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body))), token, what);
    }

    private Map<?, ?> get(String token, String methodAndQuery, String what) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(apiBase + "/" + methodAndQuery)).GET(), token, what);
    }

    private Map<?, ?> send(HttpRequest.Builder request, String token, String what) throws Exception {
        HttpResponse<String> response;
        try {
            response = http.send(request.timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IOException("Couldn't reach Slack: " + e.getMessage(), e);
        }
        int status = response.statusCode();
        if (status == 429) {
            throw new IllegalStateException("Slack rate limit reached; will try again");
        }
        if (status >= 500) {
            throw new IllegalStateException("Slack had a problem (HTTP " + status + "); will try again");
        }
        Map<?, ?> res;
        try {
            res = jsonMapper.readValue(response.body(), Map.class);
        } catch (RuntimeException notJson) {
            throw new IllegalStateException("Slack answered HTTP " + status + " with something unexpected; will try again");
        }
        if (Boolean.TRUE.equals(res.get("ok"))) {
            return res;
        }
        String error = String.valueOf(res.get("error"));
        if (TEMPORARY.contains(error)) {
            throw new IllegalStateException("Slack: " + error + "; will try again");
        }
        throw new PermanentStepException(explain(error, res, what));
    }

    private static String explain(String error, Map<?, ?> res, String what) {
        return switch (error) {
            case "channel_not_found" -> "Slack couldn't find " + what + ", or the bot can't see it";
            case "not_in_channel" -> "The Slack bot isn't in " + what + ". Invite it with /invite, then run again.";
            case "is_archived" -> "Slack " + what + " is archived";
            case "msg_too_long" -> "The message is too long for Slack";
            case "no_text" -> "The message is empty";
            case "users_not_found", "user_not_found" -> "Slack couldn't find " + what;
            case "cannot_dm_bot" -> "Slack doesn't allow direct messages to that bot";
            case "missing_scope" -> "The Slack app is missing a permission"
                    + (res.get("needed") != null ? " (" + res.get("needed") + ")" : "") + ". Add it to the app, reinstall it, then reconnect.";
            case "invalid_auth", "not_authed", "token_revoked", "token_expired", "account_inactive" ->
                    "Slack no longer accepts this account's token (" + error + "). Reconnect it on the Connections page.";
            default -> "Slack refused the request: " + error;
        };
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
