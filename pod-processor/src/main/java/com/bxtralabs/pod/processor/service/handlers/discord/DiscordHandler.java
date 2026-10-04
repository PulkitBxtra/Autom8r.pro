package com.bxtralabs.pod.processor.service.handlers.discord;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.AccountRejectedException;
import com.bxtralabs.pod.processor.service.handlers.ActionHandler;
import com.bxtralabs.pod.processor.service.handlers.AppCalls;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import com.bxtralabs.pod.processor.service.handlers.StepContext;
import com.bxtralabs.pod.processor.service.handlers.StepCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Discord actions, through the step's Discord connection (a bot token):
//   discord.send_message  channelId (an ID, or a channel link), content, replyTo? -> {id, channelId, content, timestamp}
// Mentions: users and roles named in the message are pinged; @everyone and @here are not, so data
// from a trigger can't ping a whole server.
// Not twice: each message carries a nonce made from the step run, which Discord itself refuses to
// post twice for a few minutes (enforce_nonce). If an earlier attempt may have posted longer ago,
// the channel's messages since the first attempt are checked for one from this bot with the same
// text (needs the Read Message History permission); if it can't be checked, the step stops rather
// than risk a duplicate.
// Errors the user must fix (unknown channel, bot not in the server or missing a permission, text
// too long) fail the step for good; rate limits and 5xx are retried.
@Component
@Order(1)
public class DiscordHandler implements ActionHandler {

    public static final String SEND_MESSAGE = "discord.send_message";
    static final int MAX_LENGTH = 2000;
    private static final Pattern SNOWFLAKE = Pattern.compile("\\d{15,21}");
    // https://discord.com/channels/<server>/<channel>[/<message>]
    private static final Pattern CHANNEL_LINK = Pattern.compile("https?://(?:\\w+\\.)?discord(?:app)?\\.com/channels/(?:\\d+|@me)/(\\d+)(?:/\\d+)?/?");
    // Discord's epoch (2015-01-01), for turning a time into a message ID to list messages after.
    private static final long DISCORD_EPOCH = 1420070400000L;
    // Discord asks API clients to name themselves this way.
    private static final String USER_AGENT = "DiscordBot (https://autom8r.pro, 1.0)";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final String apiBase;

    public DiscordHandler(JsonMapper jsonMapper,
                          @Value("${connectors.discord.api-base:https://discord.com/api/v10}") String apiBase) {
        this.jsonMapper = jsonMapper;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override
    public boolean supports(GraphNode node) {
        return SEND_MESSAGE.equals(node.type());
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
        String token = credentials == null ? null : credentials.get("token");
        if (token == null || token.isBlank()) {
            throw new PermanentStepException("This step needs a Discord account. Choose one in the step's setup.");
        }
        String channel = channelId(text(input.get("channelId")));
        String content = text(input.get("content"));
        if (content.isEmpty()) {
            throw new PermanentStepException("The message is empty");
        }
        if (content.codePointCount(0, content.length()) > MAX_LENGTH) {
            throw new PermanentStepException("The message is " + content.codePointCount(0, content.length())
                    + " characters long; Discord allows at most " + MAX_LENGTH);
        }
        String replyTo = text(input.get("replyTo"));
        if (!replyTo.isEmpty() && !SNOWFLAKE.matcher(replyTo).matches()) {
            throw new PermanentStepException("\"Reply to\" must be a message ID (a long number), not \"" + replyTo + "\"");
        }

        if (context != null && context.mayHaveHappened()) {
            Map<?, ?> earlier = earlierPost(token, channel, content, context);
            if (earlier != null) {
                Map<String, Object> out = output(earlier, channel, content);
                out.put("alreadyDone", true);
                return out;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("allowed_mentions", Map.of("parse", List.of("users", "roles"), "replied_user", true));
        if (!replyTo.isEmpty()) {
            body.put("message_reference", Map.of("message_id", replyTo, "fail_if_not_exists", false));
        }
        if (context != null) {
            body.put("nonce", nonce(context));
            body.put("enforce_nonce", true);
        }
        Map<?, ?> created = (Map<?, ?>) send(HttpRequest.newBuilder(URI.create(apiBase + "/channels/" + channel + "/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body))), token, channel, true);
        return output(created, channel, content);
    }

    private static Map<String, Object> output(Map<?, ?> message, String channel, String content) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(message.get("id")));
        out.put("channelId", message.get("channel_id") != null ? String.valueOf(message.get("channel_id")) : channel);
        out.put("content", content);
        out.put("timestamp", message.get("timestamp") == null ? null : String.valueOf(message.get("timestamp")));
        return out;
    }

    // A channel ID, from an ID or a link to the channel (or to a message in it).
    static String channelId(String given) throws PermanentStepException {
        if (SNOWFLAKE.matcher(given).matches()) return given;
        Matcher link = CHANNEL_LINK.matcher(given);
        if (link.matches()) return link.group(1);
        throw new PermanentStepException(given.isEmpty() ? "Choose a Discord channel"
                : "\"" + given + "\" isn't a Discord channel ID or link. Pick the channel from the list, or copy its "
                + "ID (Developer Mode → right-click the channel → Copy Channel ID).");
    }

    // At most 25 characters, the same for every attempt of the step.
    static String nonce(StepContext context) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(context.marker().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, 25);
    }

    // A message an earlier attempt of this step posted: from this bot, with this text, since the
    // first attempt started.
    private Map<?, ?> earlierPost(String token, String channel, String content, StepContext context) throws Exception {
        try {
            Map<?, ?> me = (Map<?, ?>) send(HttpRequest.newBuilder(URI.create(apiBase + "/users/@me")).GET(), token, channel, false);
            long after = Math.max(0, context.firstStartedAt() - 60_000 - DISCORD_EPOCH) << 22;
            List<?> messages = (List<?>) send(HttpRequest.newBuilder(URI.create(apiBase + "/channels/" + channel
                    + "/messages?limit=100&after=" + after)).GET(), token, channel, false);
            for (Object m : messages) {
                if (m instanceof Map<?, ?> message && content.equals(message.get("content"))
                        && message.get("author") instanceof Map<?, ?> author && String.valueOf(me.get("id")).equals(String.valueOf(author.get("id")))) {
                    return message;
                }
            }
            return null;
        } catch (AccountRejectedException e) {
            throw e; // the token itself is dead: that's the problem to report
        } catch (PermanentStepException e) {
            throw new PermanentStepException("An earlier try may already have posted this message, and Discord won't let "
                    + "Autom8r check (" + e.getMessage() + "). Not posting again to avoid a duplicate; give the bot the "
                    + "Read Message History permission in that channel so it can check next time.");
        }
    }

    private Object send(HttpRequest.Builder request, String token, String channel, boolean creates) throws Exception {
        HttpResponse<String> response = AppCalls.send(http, request.timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bot " + token)
                .header("User-Agent", USER_AGENT)
                .build(), "Discord", creates);
        int status = response.statusCode();
        if (status == 429) {
            throw new IllegalStateException("Discord rate limit reached; will try again");
        }
        if (status >= 500) {
            if (creates) throw AppCalls.uncertainServerError("Discord", status, "the message");
            throw new IllegalStateException("Discord had a problem (HTTP " + status + "); will try again");
        }
        Object body;
        try {
            body = response.body() == null || response.body().isBlank() ? Map.of() : jsonMapper.readValue(response.body(), Object.class);
        } catch (RuntimeException notJson) {
            throw new IllegalStateException("Discord answered HTTP " + status + " with something unexpected; will try again");
        }
        if (status >= 200 && status < 300) {
            return body;
        }
        Map<?, ?> error = body instanceof Map<?, ?> m ? m : Map.of();
        int code = error.get("code") instanceof Number n ? n.intValue() : 0;
        String message = error.get("message") == null ? "HTTP " + status : String.valueOf(error.get("message"));
        if (status == 401) {
            throw new AccountRejectedException("Discord no longer accepts this bot's token (" + message
                    + "). Reset the token in the Discord Developer Portal and reconnect the account.");
        }
        throw new PermanentStepException(switch (code) {
            case 10003 -> "Discord couldn't find channel " + channel;
            case 50001 -> "The Discord bot can't see channel " + channel
                    + ". Add the bot to that server and let it view the channel.";
            case 50013 -> "The Discord bot isn't allowed to do this in channel " + channel
                    + ". Give it the Send Messages permission there (and Read Message History).";
            case 50008 -> "Discord doesn't allow messages in that kind of channel";
            case 40001 -> "Discord refused the bot (" + message + "). A new bot must connect to Discord once before "
                    + "it can post; open the Discord Developer Portal, check the bot is added to the server, and try again.";
            case 50006 -> "The message is empty";
            case 50035 -> "Discord refused the message: " + message + details(error);
            default -> "Discord refused the request (HTTP " + status + "): " + message;
        });
    }

    // Discord's per-field reasons for an invalid request, e.g. "content: Must be 2000 or fewer in length."
    private static String details(Map<?, ?> error) {
        StringBuilder out = new StringBuilder();
        collect(error.get("errors"), "", out);
        return out.isEmpty() ? "" : " (" + out + ")";
    }

    private static void collect(Object node, String path, StringBuilder out) {
        if (!(node instanceof Map<?, ?> m)) return;
        if (m.get("_errors") instanceof List<?> errors) {
            for (Object e : errors) {
                if (e instanceof Map<?, ?> em && em.get("message") != null) {
                    if (!out.isEmpty()) out.append("; ");
                    out.append(path.isEmpty() ? "" : path + ": ").append(em.get("message"));
                }
            }
        }
        m.forEach((k, v) -> {
            if (!"_errors".equals(k)) collect(v, path.isEmpty() ? String.valueOf(k) : path + "." + k, out);
        });
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
