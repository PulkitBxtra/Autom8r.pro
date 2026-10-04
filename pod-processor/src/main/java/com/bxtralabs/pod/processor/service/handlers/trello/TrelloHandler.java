package com.bxtralabs.pod.processor.service.handlers.trello;

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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Trello actions, through the step's Trello connection: an API key and a token from the form, or an
// OAuth 2.0 access token from signing in with Trello (sent as a Bearer token to trello.com/1):
//   trello.create_card  listId, name, description?, position? (top|bottom), due? -> {id, name, url, listId, boardId}
//   trello.move_card    cardId (an ID, short link or card URL), listId, position?  -> {id, name, url, listId, boardId}
// Not twice: Trello has no idempotency key, but a card's ID starts with the second it was
// created. If an earlier attempt may have created the card, the list is checked for one with the
// same name and description created since the first attempt, and that one is returned instead.
// Moving a card to a list it's already in changes nothing, so moves are simply repeated.
// Credentials go in the Authorization header, never the URL, so they can't end up in an error.
// Errors the user must fix (unknown list or card, no access to the board) fail the step for good;
// rate limits and 5xx are retried.
@Component
@Order(1)
public class TrelloHandler implements ActionHandler {

    public static final String CREATE_CARD = "trello.create_card";
    public static final String MOVE_CARD = "trello.move_card";
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{24}");
    private static final Pattern SHORT_LINK = Pattern.compile("[A-Za-z0-9]{8}");
    // https://trello.com/c/<shortLink>[/<number>-<slug>]
    private static final Pattern CARD_LINK = Pattern.compile("https?://(?:www\\.)?trello\\.com/c/([A-Za-z0-9]{8})(?:/.*)?");
    private static final String CARD_FIELDS = "fields=name,desc,idList,idBoard,shortUrl";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final String keyApiBase;
    private final String oauthApiBase;

    public TrelloHandler(JsonMapper jsonMapper,
                         @Value("${connectors.trello.api-base:https://api.trello.com/1}") String keyApiBase,
                         @Value("${connectors.trello.oauth-api-base:https://trello.com/1}") String oauthApiBase) {
        this.jsonMapper = jsonMapper;
        this.keyApiBase = keyApiBase.replaceAll("/+$", "");
        this.oauthApiBase = oauthApiBase.replaceAll("/+$", "");
    }

    // How to call Trello for one step: where, and with which Authorization header.
    private record Api(String base, String auth) {
    }

    @Override
    public boolean supports(GraphNode node) {
        return CREATE_CARD.equals(node.type()) || MOVE_CARD.equals(node.type());
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
        Api auth = api(credentials);
        String listId = id(text(input.get("listId")), "list");
        String position = position(text(input.get("position")));

        if (MOVE_CARD.equals(node.type())) {
            String cardId = cardId(text(input.get("cardId")));
            // A list on another board needs that board named too.
            Map<?, ?> list = (Map<?, ?>) send(get(auth, "/lists/" + listId + "?fields=idBoard"), auth, "list " + listId, false);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("idList", listId);
            query.put("idBoard", String.valueOf(list.get("idBoard")));
            if (position != null) query.put("pos", position);
            Map<?, ?> card = (Map<?, ?>) send(HttpRequest.newBuilder(URI.create(auth.base() + "/cards/" + cardId + "?"
                    + query(query) + "&" + CARD_FIELDS)).PUT(HttpRequest.BodyPublishers.noBody()), auth, "card " + cardId, false);
            return output(card);
        }

        String name = text(input.get("name"));
        if (name.isEmpty()) {
            throw new PermanentStepException("The card needs a name");
        }
        if (name.length() > 16384) {
            throw new PermanentStepException("The card's name is too long for Trello (at most 16384 characters)");
        }
        String description = text(input.get("description"));
        if (description.length() > 16384) {
            throw new PermanentStepException("The description is too long for Trello (at most 16384 characters)");
        }
        String due = due(text(input.get("due")));

        if (context != null && context.mayHaveHappened()) {
            Map<?, ?> earlier = earlierCard(auth, listId, name, description, context);
            if (earlier != null) {
                Map<String, Object> out = output(earlier);
                out.put("alreadyDone", true);
                return out;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idList", listId);
        body.put("name", name);
        if (!description.isEmpty()) body.put("desc", description);
        body.put("pos", position == null ? "bottom" : position);
        if (due != null) body.put("due", due);
        Map<?, ?> card = (Map<?, ?>) send(HttpRequest.newBuilder(URI.create(auth.base() + "/cards?" + CARD_FIELDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body))), auth, "list " + listId, true);
        return output(card);
    }

    private static Map<String, Object> output(Map<?, ?> card) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(card.get("id")));
        out.put("name", card.get("name") == null ? null : String.valueOf(card.get("name")));
        out.put("url", card.get("shortUrl") == null ? null : String.valueOf(card.get("shortUrl")));
        out.put("listId", card.get("idList") == null ? null : String.valueOf(card.get("idList")));
        out.put("boardId", card.get("idBoard") == null ? null : String.valueOf(card.get("idBoard")));
        return out;
    }

    // A card an earlier attempt of this step created: same name and description, in the list,
    // created since a minute before the first attempt (the first 8 hex digits of an ID are the
    // second it was made).
    private Map<?, ?> earlierCard(Api auth, String listId, String name, String description, StepContext context) throws Exception {
        long since = (context.firstStartedAt() - 60_000) / 1000;
        List<?> cards = (List<?>) send(get(auth, "/lists/" + listId + "/cards?" + CARD_FIELDS), auth, "list " + listId, false);
        for (Object c : cards) {
            if (c instanceof Map<?, ?> card && name.equals(card.get("name")) && description.equals(text(card.get("desc")))
                    && createdAt(String.valueOf(card.get("id"))) >= since) {
                return card;
            }
        }
        return null;
    }

    static long createdAt(String id) {
        return ID.matcher(id).matches() ? Long.parseLong(id.substring(0, 8), 16) : 0;
    }

    private Api api(StepCredentials credentials) throws PermanentStepException {
        String accessToken = credentials == null ? null : credentials.get("access_token");
        if (accessToken != null && !accessToken.isBlank()) {
            return new Api(oauthApiBase, "Bearer " + accessToken);
        }
        String key = credentials == null ? null : credentials.get("apiKey");
        String token = credentials == null ? null : credentials.get("token");
        if (key == null || key.isBlank() || token == null || token.isBlank()) {
            throw new PermanentStepException("This step needs a Trello account. Choose one in the step's setup.");
        }
        return new Api(keyApiBase, "OAuth oauth_consumer_key=\"" + key + "\", oauth_token=\"" + token + "\"");
    }

    private HttpRequest.Builder get(Api api, String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create(api.base() + pathAndQuery)).GET();
    }

    private Object send(HttpRequest.Builder request, Api api, String what, boolean creates) throws Exception {
        HttpResponse<String> response = AppCalls.send(http, request.timeout(Duration.ofSeconds(30))
                .header("Authorization", api.auth())
                .header("Accept", "application/json")
                .build(), "Trello", creates);
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body().trim();
        if (status == 429) {
            throw new IllegalStateException("Trello rate limit reached; will try again");
        }
        if (status >= 500) {
            if (creates) throw AppCalls.uncertainServerError("Trello", status, "the card");
            throw new IllegalStateException("Trello had a problem (HTTP " + status + "); will try again");
        }
        if (status >= 200 && status < 300) {
            try {
                return jsonMapper.readValue(body, Object.class);
            } catch (RuntimeException notJson) {
                throw new IllegalStateException("Trello answered HTTP " + status + " with something unexpected; will try again");
            }
        }
        // Trello's errors are plain text ("invalid token") or JSON ({"message": "..."}).
        String message = body;
        if (body.startsWith("{")) {
            try {
                Object m = jsonMapper.readValue(body, Map.class).get("message");
                if (m != null) message = String.valueOf(m);
            } catch (RuntimeException ignored) {
                // keep the raw text
            }
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (status == 401 && (lower.contains("invalid token") || lower.contains("invalid key")
                || lower.contains("expired token") || lower.contains("token expired") || lower.contains("revoked"))) {
            throw new AccountRejectedException("Trello no longer accepts this account's "
                    + (api.auth().startsWith("Bearer ") ? "sign-in" : "key and token") + " (" + message
                    + "). Reconnect the account on the Connections page.");
        }
        if (status == 401) {
            // A valid token without access to that board or card.
            throw new PermanentStepException("This Trello account can't reach " + what
                    + ". Check it's a member of that board, or connect an account that is.");
        }
        if (status == 404 || lower.contains("not found")) {
            throw new PermanentStepException("Trello couldn't find " + what);
        }
        if (lower.contains("invalid id") || lower.contains("invalid objectid")) {
            throw new PermanentStepException("Trello says " + what + " isn't a valid ID");
        }
        throw new PermanentStepException("Trello refused the request (HTTP " + status + "): " + abbreviate(message));
    }

    // A list ID as Trello writes them (24 hex digits).
    static String id(String given, String what) throws PermanentStepException {
        if (ID.matcher(given).matches()) return given;
        throw new PermanentStepException(given.isEmpty() ? "Choose a Trello " + what
                : "\"" + abbreviate(given) + "\" isn't a Trello " + what + " ID. Pick the " + what + " from the list.");
    }

    // A card's ID or short link, from either, or from a link to the card.
    static String cardId(String given) throws PermanentStepException {
        if (ID.matcher(given).matches() || SHORT_LINK.matcher(given).matches()) return given;
        Matcher link = CARD_LINK.matcher(given);
        if (link.matches()) return link.group(1);
        throw new PermanentStepException(given.isEmpty() ? "Which card? Set Card to an ID or a link to the card"
                : "\"" + abbreviate(given) + "\" isn't a Trello card ID or link");
    }

    private static String position(String given) throws PermanentStepException {
        if (given.isEmpty()) return null;
        String p = given.toLowerCase(Locale.ROOT);
        if (p.equals("top") || p.equals("bottom")) return p;
        throw new PermanentStepException("Position must be top or bottom, not \"" + abbreviate(given) + "\"");
    }

    private static String due(String given) throws PermanentStepException {
        if (given.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(given).toInstant().toString();
        } catch (DateTimeParseException notWithZone) {
            try {
                return java.time.LocalDate.parse(given).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString();
            } catch (DateTimeParseException e) {
                throw new PermanentStepException("Due date must be a date like 2026-10-31 or a time like 2026-10-31T17:00:00Z, not \""
                        + abbreviate(given) + "\"");
            }
        }
    }

    private static String query(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        params.forEach((k, v) -> {
            if (!out.isEmpty()) out.append('&');
            out.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
