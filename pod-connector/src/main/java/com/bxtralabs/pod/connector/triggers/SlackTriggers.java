package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.connections.ConnectorRegistry;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.model.TriggerSubscription;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

// Slack triggers, from the Events API:
//   trg_slack_new_message  "message" events in the chosen channel (bot event message.channels)
//   trg_slack_new_mention  "app_mention" events: someone mentions the bot
// Slack can't subscribe to events per workflow: a Slack app sends all its events to the one
// Request URL set in its Event Subscriptions. So registering only checks the account and channel
// and records what events are matched on:
//   - connected with "Connect with Slack" (this server's app): events arrive at /hooks/slack,
//     signed with the server's signing secret, and are matched by workspace (team) id;
//   - connected with a bot token of the user's own app: events arrive at
//     /hooks/slack/connections/<connection id>, signed with the signing secret saved on the
//     connection, and are matched by connection.
// Messages the connection's own bot posts are ignored, so a workflow that posts to the channel
// it listens on doesn't start itself.
@Component
public class SlackTriggers implements AppTriggerRegistrar {

    public static final String NEW_MESSAGE = "trg_slack_new_message";
    public static final String NEW_MENTION = "trg_slack_new_mention";
    static final String VIA_SERVER = "server";
    static final String VIA_OWN = "own";
    private static final Pattern CHANNEL_ID = Pattern.compile("[CG][A-Z0-9]{6,}");
    // Message subtypes that are still someone posting a message.
    private static final Set<String> POSTED_SUBTYPES = Set.of("thread_broadcast", "file_share", "me_message");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final ConnectionRepository connections;
    private final String apiBase;
    private final String publicUrl;
    private final boolean serverSigningSecretSet;

    public SlackTriggers(JsonMapper jsonMapper, ConnectionRepository connections,
                         @Value("${connectors.slack.api-base:https://slack.com/api}") String apiBase,
                         @Value("${triggers.public-url:}") String publicUrl,
                         @Value("${connectors.slack.signing-secret:}") String serverSigningSecret) {
        this.jsonMapper = jsonMapper;
        this.connections = connections;
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.publicUrl = publicUrl == null ? "" : publicUrl.trim().replaceAll("/+$", "");
        this.serverSigningSecretSet = serverSigningSecret != null && !serverSigningSecret.isBlank();
    }

    @Override
    public boolean supports(String appId) {
        return "app_slack".equals(appId);
    }

    // Where a connection's own Slack app must send its events.
    public String eventsUrl(String connectionId) {
        return publicUrl + "/hooks/slack/connections/" + connectionId;
    }

    @Override
    public Registration register(TriggerSubscription s, Map<String, String> credentials, String hookUrl, String secret)
            throws TriggerSetupException {
        Connection c = connections.findById(s.getConnectionId())
                .orElseThrow(() -> new TriggerSetupException("The trigger's account no longer exists; choose another"));
        Map<String, Object> meta = new LinkedHashMap<>();
        if (Connection.AUTH_TOKEN.equals(c.getAuthType())) {
            String signing = credentials.get(ConnectorRegistry.SLACK_SIGNING_SECRET);
            if (signing == null || signing.isBlank()) {
                throw new TriggerSetupException("Slack triggers need your Slack app's signing secret. Edit this Slack"
                        + " connection and add it (your Slack app → Basic Information → Signing Secret).");
            }
            meta.put("via", VIA_OWN);
            meta.put("eventsUrl", eventsUrl(c.getId()));
        } else if (c.getOauthClientId() != null) {
            throw new TriggerSetupException("Slack triggers don't work with a connection made through your own OAuth app."
                    + " Connect with Slack, or connect with your app's bot token and signing secret.");
        } else {
            if (!serverSigningSecretSet) {
                throw new TriggerSetupException("Slack triggers aren't set up on this server (SLACK_SIGNING_SECRET isn't set)");
            }
            meta.put("via", VIA_SERVER);
        }

        String token = token(credentials);
        Map<?, ?> me = call(token, "auth.test", Map.of());
        if (!ok(me)) {
            throw new TriggerSetupException("invalid_auth".equals(me.get("error")) || "token_revoked".equals(me.get("error"))
                    ? "Slack no longer accepts this account's token. Reconnect it, then turn the workflow on again."
                    : "Slack couldn't check this account (" + me.get("error") + ")");
        }
        s.setRoutingKey(String.valueOf(me.get("team_id")));
        meta.put("team", me.get("team"));
        meta.put("botUserId", me.get("user_id"));
        meta.put("botId", me.get("bot_id"));

        if (NEW_MESSAGE.equals(s.getTriggerId())) {
            Map<?, ?> channel = channel(token, s);
            if (!Boolean.TRUE.equals(channel.get("is_member"))) {
                throw new TriggerSetupException("The Slack bot isn't in #" + channel.get("name") + ", so Slack won't send"
                        + " its messages. In Slack, type /invite @" + me.get("user") + " in #" + channel.get("name")
                        + ", then turn the workflow on again.");
            }
            meta.put("channelId", channel.get("id"));
            meta.put("channelName", channel.get("name"));
        }
        s.setMeta(meta);
        return new Registration(null, null); // nothing registered with Slack, nothing to remove
    }

    @Override
    public void unregister(TriggerSubscription subscription, Map<String, String> credentials) {
        // Nothing was registered with Slack.
    }

    // The trigger's data for an event, or empty when this subscription doesn't start on it.
    public Optional<Map<String, Object>> toTriggerBody(TriggerSubscription s, Map<?, ?> event) {
        Map<String, Object> meta = s.getMeta() == null ? Map.of() : s.getMeta();
        Object type = event.get("type");
        Object subtype = event.get("subtype");
        boolean ownBot = (event.get("bot_id") != null && event.get("bot_id").equals(meta.get("botId")))
                || (event.get("user") != null && event.get("user").equals(meta.get("botUserId")));
        if (ownBot) {
            return Optional.empty();
        }
        if (NEW_MESSAGE.equals(s.getTriggerId())) {
            if (!"message".equals(type) || (subtype != null && !POSTED_SUBTYPES.contains(String.valueOf(subtype)))
                    || !Objects.equals(event.get("channel"), meta.get("channelId"))) {
                return Optional.empty();
            }
        } else if (NEW_MENTION.equals(s.getTriggerId())) {
            if (!"app_mention".equals(type)) {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", event.get("text"));
        out.put("user", event.get("user"));
        out.put("channel", event.get("channel"));
        if (meta.get("channelName") != null) {
            out.put("channelName", meta.get("channelName"));
        }
        out.put("ts", event.get("ts"));
        out.put("threadTs", event.get("thread_ts"));
        out.put("team", meta.get("team"));
        if (event.get("files") instanceof List<?> files) {
            out.put("files", files.stream().map(f -> f instanceof Map<?, ?> m ? m.get("name") : f).toList());
        }
        return Optional.of(out);
    }

    // The configured channel ("#general", "general" or an id like C0123ABCD).
    private Map<?, ?> channel(String token, TriggerSubscription s) throws TriggerSetupException {
        Object raw = s.getConfig() == null ? null : s.getConfig().get("channel");
        String wanted = raw == null ? "" : String.valueOf(raw).trim().replaceFirst("^#", "");
        if (wanted.isEmpty()) {
            throw new TriggerSetupException("Choose the channel to listen to");
        }
        if (CHANNEL_ID.matcher(wanted).matches()) {
            Map<?, ?> info = call(token, "conversations.info", Map.of("channel", wanted));
            if (ok(info) && info.get("channel") instanceof Map<?, ?> channel) {
                return channel;
            }
            throw new TriggerSetupException("Slack couldn't find channel " + wanted + " (" + info.get("error") + ")");
        }
        String cursor = "";
        for (int page = 0; page < 50; page++) {
            Map<?, ?> list = call(token, "conversations.list", Map.of("types", "public_channel",
                    "exclude_archived", "true", "limit", "1000", "cursor", cursor));
            if (!ok(list)) {
                throw new TriggerSetupException("missing_scope".equals(list.get("error"))
                        ? "This Slack account can't list channels (it needs the channels:read permission). Reconnect it."
                        : "Slack couldn't list channels (" + list.get("error") + ")");
            }
            if (list.get("channels") instanceof List<?> channels) {
                for (Object c : channels) {
                    if (c instanceof Map<?, ?> channel && wanted.equals(channel.get("name"))) {
                        return channel;
                    }
                }
            }
            Object next = list.get("response_metadata") instanceof Map<?, ?> m ? m.get("next_cursor") : null;
            if (next == null || String.valueOf(next).isEmpty()) break;
            cursor = String.valueOf(next);
        }
        throw new TriggerSetupException("There's no public channel #" + wanted + " in this Slack workspace."
                + " For a private channel, use its channel id.");
    }

    private static boolean ok(Map<?, ?> response) {
        return Boolean.TRUE.equals(response.get("ok"));
    }

    private static String token(Map<String, String> credentials) {
        return credentials.get("access_token") != null ? credentials.get("access_token") : credentials.get("token");
    }

    private Map<?, ?> call(String token, String method, Map<String, String> params) throws TriggerSetupException {
        StringBuilder form = new StringBuilder();
        params.forEach((k, v) -> {
            if (v == null || v.isEmpty()) return;
            if (!form.isEmpty()) form.append('&');
            form.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/" + method))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new TriggerSetupException("Slack answered HTTP " + response.statusCode() + " while setting up the trigger. Try again.");
            }
            return jsonMapper.readValue(response.body(), Map.class);
        } catch (TriggerSetupException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new TriggerSetupException("Couldn't reach Slack to set up the trigger. Try again.");
        }
    }
}
