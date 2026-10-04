package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.connections.OAuthClientService;
import com.bxtralabs.pod.connector.connections.OAuthProviders;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

// Trello triggers as webhooks on a board, created with the trigger's connection:
//   trg_trello_new_card    a card is created on the board (optionally: in one list)
//   trg_trello_card_moved  a card is moved into a list (the board is the list's)
// Trello checks the callback with a HEAD request before creating the webhook, then signs each
// delivery with the secret of the app the connection comes from (returned as the registration's
// secret and kept encrypted):
//   signed in with Trello (OAuth 2.0)  the OAuth client's secret (the server's, or the user's own)
//   a key and token                    the Power-Up's API secret: the server's for its own key
//                                      (connectors.trello.api-key/-secret), else the "apiSecret"
//                                      the user added to the connection
// The callback URL is part of what's signed, so it's kept with the subscription (meta.callbackUrl).
// Also turns a delivery into the trigger's data (toTriggerBody), or empty for actions it ignores.
@Component
public class TrelloTriggers implements AppTriggerRegistrar {

    public static final String NEW_CARD = "trg_trello_new_card";
    public static final String CARD_MOVED = "trg_trello_card_moved";
    // The ways a card appears on a board.
    private static final Set<String> CARD_ADDED = Set.of("createCard", "copyCard", "convertToCardFromCheckItem", "moveCardToBoard");
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{24}");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final ConnectionRepository connections;
    private final OAuthProviders providers;
    private final OAuthClientService clients;
    private final String keyApi;
    private final String oauthApi;
    private final String serverKey;
    private final String serverSecret;

    public TrelloTriggers(JsonMapper jsonMapper, ConnectionRepository connections, OAuthProviders providers,
                          OAuthClientService clients,
                          @Value("${connectors.trello.api-base:https://api.trello.com/1}") String keyApi,
                          @Value("${connectors.trello.oauth-api-base:https://trello.com/1}") String oauthApi,
                          @Value("${connectors.trello.api-key:}") String serverKey,
                          @Value("${connectors.trello.api-secret:}") String serverSecret) {
        this.jsonMapper = jsonMapper;
        this.connections = connections;
        this.providers = providers;
        this.clients = clients;
        this.keyApi = keyApi.replaceAll("/+$", "");
        this.oauthApi = oauthApi.replaceAll("/+$", "");
        this.serverKey = serverKey == null ? "" : serverKey.trim();
        this.serverSecret = serverSecret == null ? "" : serverSecret.trim();
    }

    @Override
    public boolean supports(String appId) {
        return "app_trello".equals(appId);
    }

    @Override
    public Registration register(TriggerSubscription s, Map<String, String> credentials, String hookUrl, String unused)
            throws TriggerSetupException {
        String secret = signingSecret(s, credentials);
        String listId = id(s, "listId", CARD_MOVED.equals(s.getTriggerId()), "list");
        String boardId = CARD_MOVED.equals(s.getTriggerId()) ? null : id(s, "boardId", true, "board");
        Map<String, Object> meta = new LinkedHashMap<>();
        if (listId != null) {
            // The list's board: where a moved card's webhook goes, and a check that a New Card
            // list is on the chosen board.
            Map<?, ?> list = json(call(credentials, "GET", "/lists/" + listId + "?fields=name,idBoard", null), "list " + listId);
            String listBoard = String.valueOf(list.get("idBoard"));
            if (boardId != null && !boardId.equals(listBoard)) {
                throw new TriggerSetupException("The list \"" + list.get("name") + "\" isn't on the chosen board; pick one of its lists");
            }
            boardId = listBoard;
            meta.put("listName", list.get("name"));
        }
        meta.put("callbackUrl", hookUrl);
        meta.put("boardId", boardId);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("callbackURL", hookUrl);
        form.put("idModel", boardId);
        form.put("description", "Autom8r workflow " + s.getWorkflowId());
        Map<?, ?> webhook = json(call(credentials, "POST", "/webhooks", form), "board " + boardId);
        s.setMeta(meta);
        return new Registration(String.valueOf(webhook.get("id")), secret);
    }

    @Override
    public void unregister(TriggerSubscription s, Map<String, String> credentials) {
        if (s.getExternalId() == null) return;
        try {
            call(credentials, "DELETE", "/webhooks/" + s.getExternalId(), null);
        } catch (Exception e) {
            // Already gone, or Trello unreachable: a leftover webhook is told 410 Gone on its next
            // delivery, which makes Trello delete it.
            System.out.println("Couldn't remove Trello webhook " + s.getExternalId() + ": " + e.getMessage());
        }
    }

    // The trigger's data for a delivery, or empty when the action isn't one this trigger starts on.
    public Optional<Map<String, Object>> toTriggerBody(TriggerSubscription s, Map<?, ?> payload) {
        if (!(payload.get("action") instanceof Map<?, ?> action) || !(action.get("data") instanceof Map<?, ?> data)
                || !(data.get("card") instanceof Map<?, ?> card)) {
            return Optional.empty();
        }
        String type = String.valueOf(action.get("type"));
        String wantedList = s.getConfig() == null || s.getConfig().get("listId") == null ? "" : String.valueOf(s.getConfig().get("listId")).trim();
        Map<?, ?> board = data.get("board") instanceof Map<?, ?> b ? b : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", card.get("id"));
        out.put("name", card.get("name"));
        out.put("url", card.get("shortLink") == null ? null : "https://trello.com/c/" + card.get("shortLink"));
        if (NEW_CARD.equals(s.getTriggerId())) {
            Map<?, ?> list = data.get("list") instanceof Map<?, ?> l ? l : Map.of();
            if (!CARD_ADDED.contains(type) || (!wantedList.isEmpty() && !wantedList.equals(list.get("id")))) {
                return Optional.empty();
            }
            out.put("listId", list.get("id"));
            out.put("listName", list.get("name"));
        } else {
            if (!"updateCard".equals(type) || !(data.get("listAfter") instanceof Map<?, ?> after)
                    || !wantedList.equals(after.get("id"))) {
                return Optional.empty();
            }
            Map<?, ?> before = data.get("listBefore") instanceof Map<?, ?> l ? l : Map.of();
            out.put("fromListId", before.get("id"));
            out.put("fromListName", before.get("name"));
            out.put("listId", after.get("id"));
            out.put("listName", after.get("name"));
        }
        out.put("boardId", board.get("id"));
        out.put("boardName", board.get("name"));
        out.put("by", action.get("memberCreator") instanceof Map<?, ?> m ? m.get("fullName") : null);
        out.put("at", action.get("date"));
        return Optional.of(out);
    }

    // The secret Trello will sign this connection's webhook deliveries with.
    private String signingSecret(TriggerSubscription s, Map<String, String> credentials) throws TriggerSetupException {
        Connection c = connections.findById(s.getConnectionId())
                .orElseThrow(() -> new TriggerSetupException("The trigger's account no longer exists; choose another"));
        if (Connection.AUTH_OAUTH.equals(c.getAuthType())) {
            OAuthProviders.Provider provider = providers.find("trello")
                    .orElseThrow(() -> new TriggerSetupException("Trello sign-in isn't available on this server"));
            try {
                return clients.credentialsFor(provider, c.getOauthClientId()).clientSecret();
            } catch (IllegalStateException e) {
                throw new TriggerSetupException(e.getMessage());
            }
        }
        String own = credentials.get(com.bxtralabs.pod.connector.connections.ConnectorRegistry.TRELLO_API_SECRET);
        if (own != null && !own.isBlank()) return own.trim();
        if (!serverKey.isEmpty() && !serverSecret.isEmpty() && serverKey.equals(credentials.get("apiKey"))) return serverSecret;
        throw new TriggerSetupException("Trello signs trigger events with the API secret of the Power-Up your key comes from. "
                + "Reconnect this Trello account and add that API secret (trello.com/power-ups/admin -> your Power-Up -> "
                + "API key -> Secret), then turn the workflow on again.");
    }

    private static String id(TriggerSubscription s, String key, boolean required, String what) throws TriggerSetupException {
        Object v = s.getConfig() == null ? null : s.getConfig().get(key);
        String id = v == null ? "" : String.valueOf(v).trim();
        if (id.isEmpty() && !required) return null;
        if (!ID.matcher(id).matches()) {
            throw new TriggerSetupException(id.isEmpty() ? "Choose a Trello " + what : "\"" + id + "\" isn't a Trello " + what + " ID; pick one from the list");
        }
        return id;
    }

    private Map<?, ?> json(HttpResponse<String> response, String what) throws TriggerSetupException {
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body().trim();
        if (status >= 200 && status < 300) {
            try {
                return jsonMapper.readValue(body, Map.class);
            } catch (RuntimeException e) {
                throw new TriggerSetupException("Trello answered with something unexpected. Try again.");
            }
        }
        String message = body.startsWith("{") ? messageOf(body) : body;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (status == 401 && (lower.contains("invalid token") || lower.contains("invalid key") || lower.contains("expired"))) {
            throw new TriggerSetupException("Trello no longer accepts this account. Reconnect it, then turn the workflow on again.");
        }
        if (status == 401 || status == 403) {
            throw new TriggerSetupException("This Trello account can't reach " + what + ". Check it's a member of that board"
                    + " (and, if you signed in with Trello, that the board is in the workspace you connected).");
        }
        if (lower.contains("did not return 200")) {
            throw new TriggerSetupException("Trello couldn't reach Autom8r's trigger address to create the webhook (" + message
                    + "). The server's public address (TRIGGERS_PUBLIC_URL) must be reachable from the internet.");
        }
        if (status == 404 || lower.contains("not found")) {
            throw new TriggerSetupException("Trello couldn't find " + what);
        }
        throw new TriggerSetupException("Trello refused the webhook (HTTP " + status + "): " + message);
    }

    private String messageOf(String body) {
        try {
            Object m = jsonMapper.readValue(body, Map.class).get("message");
            return m == null ? body : String.valueOf(m);
        } catch (RuntimeException e) {
            return body;
        }
    }

    // With a key and token (OAuth 1-style header) or an OAuth 2.0 access token (Bearer, trello.com).
    private HttpResponse<String> call(Map<String, String> credentials, String method, String path, Map<String, String> form)
            throws TriggerSetupException {
        String access = credentials.get("access_token");
        boolean oauth = access != null && !access.isBlank();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create((oauth ? oauthApi : keyApi) + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", oauth ? "Bearer " + access
                            : "OAuth oauth_consumer_key=\"" + credentials.get("apiKey") + "\", oauth_token=\"" + credentials.get("token") + "\"")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .method(method, form == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(form(form)))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new TriggerSetupException("Couldn't reach Trello to set up the trigger. Try again.");
        }
    }

    private static String form(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        params.forEach((k, v) -> {
            if (!out.isEmpty()) out.append('&');
            out.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return out.toString();
    }
}
