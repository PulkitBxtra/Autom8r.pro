package com.bxtralabs.pod.processor.service.handlers.slack;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// Slack actions, through the step's Slack connection (a bot token):
//   slack.post_message  channel ("#general" or an ID), text, threadTs? -> {channel, ts, text}
//   slack.send_dm       user (an ID like U012AB3CD, or an email), text -> {channel, ts, user}
// Not twice: messages carry Slack message metadata naming the step run. If an earlier attempt
// may have posted (StepContext.mayHaveHappened), the channel's recent history is checked for it
// first (needs the channels:history / groups:history / im:history scope, and channels:read to
// find a channel by name); if it can't be checked, the step stops rather than risk a duplicate.
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
    // Slack's own trouble after a post was accepted: it may have been posted anyway.
    private static final Set<String> MAYBE_POSTED = Set.of("internal_error", "fatal_error", "request_timeout");
    private static final Pattern CHANNEL_ID = Pattern.compile("[CGD][A-Z0-9]{6,}");
    private static final String EVENT_TYPE = "autom8r_step";

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
        return execute(node, input, credentials, null);
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input, StepCredentials credentials,
                                       StepContext context) throws Exception {
        String token = credentials == null ? null : credentials.get("access_token") != null
                ? credentials.get("access_token") : credentials.get("token");
        if (token == null || token.isBlank()) {
            throw new PermanentStepException("This step needs a Slack account. Choose one in the step's setup.");
        }
        String text = text(input.get("text"));

        if (POST_MESSAGE.equals(node.type())) {
            String channel = text(input.get("channel"));
            String name = channel.startsWith("#") ? channel.substring(1) : channel;
            if (context != null && context.mayHaveHappened()) {
                Map<?, ?> earlier = earlierPost(token, channelId(token, name, "channel " + channel), context);
                if (earlier != null) {
                    return Map.of("channel", String.valueOf(earlier.get("channel")), "ts", String.valueOf(earlier.get("ts")),
                            "text", text, "alreadyDone", true);
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("channel", name);
            body.put("text", text);
            if (!text(input.get("threadTs")).isBlank()) body.put("thread_ts", text(input.get("threadTs")));
            addMetadata(body, context);
            Map<?, ?> res = post(token, body, "channel " + channel);
            return Map.of("channel", String.valueOf(res.get("channel")), "ts", String.valueOf(res.get("ts")), "text", text);
        }

        String user = text(input.get("user"));
        String userId = user;
        if (user.contains("@")) {
            Map<?, ?> found = get(token, "users.lookupByEmail?email=" + URLEncoder.encode(user, StandardCharsets.UTF_8), "user " + user);
            userId = String.valueOf(((Map<?, ?>) found.get("user")).get("id"));
        }
        // Opening a DM that's already open just returns it, so this is safe to repeat.
        Map<?, ?> opened = call(token, "conversations.open", Map.of("users", userId), "user " + user);
        String channel = String.valueOf(((Map<?, ?>) opened.get("channel")).get("id"));
        if (context != null && context.mayHaveHappened()) {
            Map<?, ?> earlier = earlierPost(token, channel, context);
            if (earlier != null) {
                return Map.of("channel", channel, "ts", String.valueOf(earlier.get("ts")), "user", userId, "alreadyDone", true);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>(Map.of("channel", channel, "text", text));
        addMetadata(body, context);
        Map<?, ?> res = post(token, body, "user " + user);
        return Map.of("channel", channel, "ts", String.valueOf(res.get("ts")), "user", userId);
    }

    private static void addMetadata(Map<String, Object> body, StepContext context) {
        if (context != null) {
            body.put("metadata", Map.of("event_type", EVENT_TYPE, "event_payload", Map.of("step", context.marker())));
        }
    }

    // chat.postMessage: the one call that creates something, so a lost answer is uncertain.
    private Map<?, ?> post(String token, Map<String, Object> body, String what) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(apiBase + "/chat.postMessage"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body))), token, what, true);
    }

    // The channel's ID: given one already, or looked up by name (a page of channels at a time).
    private String channelId(String token, String channel, String what) throws Exception {
        if (CHANNEL_ID.matcher(channel).matches()) return channel;
        String cursor = "";
        for (int page = 0; page < 10; page++) {
            String query = "conversations.list?types=public_channel,private_channel&exclude_archived=true&limit=200"
                    + (cursor.isEmpty() ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            Map<?, ?> res = checking(() -> get(token, query, what));
            for (Object c : (List<?>) res.get("channels")) {
                if (c instanceof Map<?, ?> m && channel.equals(m.get("name"))) return String.valueOf(m.get("id"));
            }
            Object next = res.get("response_metadata") instanceof Map<?, ?> meta ? meta.get("next_cursor") : null;
            cursor = next == null ? "" : String.valueOf(next);
            if (cursor.isBlank()) break;
        }
        throw new PermanentStepException("Slack couldn't find " + what + ", or the bot can't see it");
    }

    // A message an earlier attempt of this step posted to the channel, if any.
    private Map<?, ?> earlierPost(String token, String channelId, StepContext context) throws Exception {
        long oldest = (context.firstStartedAt() - 60_000) / 1000;
        Map<?, ?> res = checking(() -> get(token, "conversations.history?channel=" + channelId + "&oldest=" + oldest
                + "&include_all_metadata=true&limit=100", "that conversation"));
        for (Object item : (List<?>) res.get("messages")) {
            if (item instanceof Map<?, ?> m && m.get("metadata") instanceof Map<?, ?> meta
                    && EVENT_TYPE.equals(meta.get("event_type")) && meta.get("event_payload") instanceof Map<?, ?> payload
                    && context.marker().equals(payload.get("step"))) {
                Map<Object, Object> found = new LinkedHashMap<>(m);
                found.put("channel", channelId);
                return found;
            }
        }
        return null;
    }

    private interface Lookup {
        Map<?, ?> get() throws Exception;
    }

    // A lookup made to check for an earlier attempt's message. If Slack won't let us look (a
    // missing scope), stop instead of posting what may be a duplicate.
    private static Map<?, ?> checking(Lookup lookup) throws Exception {
        try {
            return lookup.get();
        } catch (PermanentStepException e) {
            throw new PermanentStepException("An earlier try may already have posted this message, and Slack won't let "
                    + "Autom8r check (" + e.getMessage() + "). Not posting again to avoid a duplicate; give the Slack app "
                    + "the channels:read and channels:history permissions (groups:history, im:history for private "
                    + "channels and DMs) so it can check next time.");
        }
    }

    private Map<?, ?> call(String token, String method, Map<String, Object> body, String what) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(apiBase + "/" + method))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body))), token, what, false);
    }

    private Map<?, ?> get(String token, String methodAndQuery, String what) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(apiBase + "/" + methodAndQuery)).GET(), token, what, false);
    }

    private Map<?, ?> send(HttpRequest.Builder request, String token, String what, boolean creates) throws Exception {
        HttpResponse<String> response = AppCalls.send(http, request.timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).build(), "Slack", creates);
        int status = response.statusCode();
        if (status == 429) {
            throw new IllegalStateException("Slack rate limit reached; will try again");
        }
        if (status >= 500) {
            if (creates) throw AppCalls.uncertainServerError("Slack", status, "the message");
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
        if (creates && MAYBE_POSTED.contains(error)) {
            throw new UncertainStepException("Slack: " + error + "; the message may have been posted anyway, "
                    + "so the next try checks before posting again");
        }
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
