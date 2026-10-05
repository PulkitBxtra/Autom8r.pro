package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.model.TriggerSubscription;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

// Notion triggers through this server's Notion public integration (Connect with Notion):
//   trg_notion_new_page    page.created, for a page in the chosen database
//   trg_notion_updated_db  page.properties_updated / page.content_updated, for a page in it
// Notion has no API for webhook subscriptions: the integration's one subscription is set up by hand
// (its Webhooks tab -> <triggers.public-url>/hooks/notion) and signs every event with the
// verification token, kept as NOTION_WEBHOOK_SECRET. So nothing is registered with Notion here:
// turning a workflow on checks the database is shared with the account and remembers the account's
// bot id (routingKey), which is how an event (listing the bots that can see the page) finds it.
// Each matching event's page is read with the workflow's account: that's where its database, title,
// URL and properties come from. Events caused only by the account's own bot (a Create Page step
// through it) are ignored, so a workflow writing to the database it watches can't trigger itself
// forever; changes by people, or by other integrations, still count.
@Component
public class NotionTriggers implements AppTriggerRegistrar {

    public static final String NEW_PAGE = "trg_notion_new_page";
    public static final String UPDATED = "trg_notion_updated_db";
    static final String VERSION = "2022-06-28";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final ConnectionRepository connections;
    private final String apiBase;
    private final boolean configured;

    public NotionTriggers(JsonMapper jsonMapper, ConnectionRepository connections,
                          @Value("${connectors.notion.api-base:https://api.notion.com/v1}") String apiBase,
                          @Value("${connectors.notion.webhook-secret:}") String webhookSecret) {
        this.jsonMapper = jsonMapper;
        this.connections = connections;
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.configured = webhookSecret != null && !webhookSecret.isBlank();
    }

    @Override
    public boolean supports(String appId) {
        return "app_notion".equals(appId);
    }

    static boolean matches(String triggerId, String eventType) {
        return NEW_PAGE.equals(triggerId) ? "page.created".equals(eventType)
                : "page.properties_updated".equals(eventType) || "page.content_updated".equals(eventType);
    }

    @Override
    public Registration register(TriggerSubscription s, Map<String, String> credentials, String hookUrl, String unused)
            throws TriggerSetupException {
        if (!configured) {
            throw new TriggerSetupException("Notion triggers aren't set up on this server yet: its Notion integration needs a "
                    + "webhook subscription to <public address>/hooks/notion, verified, with NOTION_WEBHOOK_SECRET set.");
        }
        Connection c = connections.findById(s.getConnectionId())
                .orElseThrow(() -> new TriggerSetupException("The trigger's account no longer exists; choose another"));
        if (!Connection.AUTH_OAUTH.equals(c.getAuthType()) || c.getOauthClientId() != null) {
            throw new TriggerSetupException("Notion triggers work with accounts connected through Autom8r's Notion sign-in "
                    + "(Connect with Notion). Connect one that way and choose it here.");
        }
        String token = credentials.get("access_token");
        String database = normalize(s.getConfig() == null ? null : s.getConfig().get("databaseId"));
        if (database == null) {
            throw new TriggerSetupException("Choose a Notion database");
        }
        Map<?, ?> db = get(token, "/databases/" + database);
        if (db == null) {
            throw new TriggerSetupException("This Notion account can't see that database. In Notion, open it and share it "
                    + "with the Autom8r connection (••• -> Connections), then turn the workflow on again.");
        }
        Map<?, ?> me = get(token, "/users/me");
        if (me == null || me.get("id") == null) {
            throw new TriggerSetupException("Notion didn't say which integration this account is. Reconnect it and try again.");
        }
        s.setRoutingKey(String.valueOf(me.get("id")));
        s.setMeta(Map.of("databaseTitle", plain(db.get("title"))));
        return new Registration(null, null); // nothing registered with Notion
    }

    @Override
    public void unregister(TriggerSubscription subscription, Map<String, String> credentials) {
        // Nothing was registered with Notion.
    }

    // Whether the event was caused only by this bot (a step writing to Notion through the account).
    static boolean onlyBy(String botId, Map<?, ?> event) {
        if (botId == null || !(event.get("authors") instanceof List<?> authors) || authors.isEmpty()) return false;
        return authors.stream().allMatch(a -> a instanceof Map<?, ?> m && botId.equals(String.valueOf(m.get("id"))));
    }

    // The trigger's data for an event's page, read with the workflow's account; empty when the page
    // isn't in the watched database (or can't be read: no longer shared, deleted).
    public Optional<Map<String, Object>> toTriggerBody(TriggerSubscription s, Map<?, ?> event, Map<String, String> credentials)
            throws TriggerSetupException {
        if (!matches(s.getTriggerId(), String.valueOf(event.get("type")))
                || !(event.get("entity") instanceof Map<?, ?> entity) || !"page".equals(entity.get("type"))) {
            return Optional.empty();
        }
        Map<?, ?> page = get(credentials.get("access_token"), "/pages/" + entity.get("id"));
        if (page == null || !(page.get("parent") instanceof Map<?, ?> parent)
                || !Objects.equals(normalize(s.getConfig().get("databaseId")), normalize(parent.get("database_id")))) {
            return Optional.empty();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", page.get("id"));
        out.put("url", page.get("url"));
        Map<String, Object> properties = new LinkedHashMap<>();
        String title = "";
        if (page.get("properties") instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> p : props.entrySet()) {
                if (!(p.getValue() instanceof Map<?, ?> prop)) continue;
                Object value = simple(prop);
                properties.put(String.valueOf(p.getKey()), value);
                if ("title".equals(prop.get("type"))) title = String.valueOf(value);
            }
        }
        out.put("title", title);
        out.put("properties", properties);
        out.put("databaseId", parent.get("database_id"));
        out.put("event", event.get("type"));
        out.put("createdTime", page.get("created_time"));
        out.put("lastEditedTime", page.get("last_edited_time"));
        if (event.get("data") instanceof Map<?, ?> data && data.get("updated_properties") instanceof List<?> updated) {
            out.put("updatedProperties", updated);
        }
        return Optional.of(out);
    }

    // A property's value in plain form: text as text, numbers, checkboxes, a select's name, a list
    // of multi-select names, a date's start, people's names, a relation's ids...
    static Object simple(Map<?, ?> prop) {
        String type = String.valueOf(prop.get("type"));
        Object v = prop.get(type);
        return switch (type) {
            case "title", "rich_text" -> plain(v);
            case "select", "status" -> v instanceof Map<?, ?> m ? m.get("name") : null;
            case "multi_select" -> v instanceof List<?> l ? l.stream().map(x -> x instanceof Map<?, ?> m ? m.get("name") : x).toList() : List.of();
            case "date" -> v instanceof Map<?, ?> m ? m.get("start") : null;
            case "people" -> v instanceof List<?> l ? l.stream().map(x -> x instanceof Map<?, ?> m ? m.get("name") : x).toList() : List.of();
            case "relation" -> v instanceof List<?> l ? l.stream().map(x -> x instanceof Map<?, ?> m ? m.get("id") : x).toList() : List.of();
            case "formula" -> v instanceof Map<?, ?> m ? m.get(String.valueOf(m.get("type"))) : null;
            case "unique_id" -> v instanceof Map<?, ?> m ? (m.get("prefix") == null ? "" : m.get("prefix") + "-") + m.get("number") : null;
            default -> v; // number, checkbox, url, email, phone_number, created_time... already plain
        };
    }

    static String plain(Object richText) {
        if (!(richText instanceof List<?> parts)) return "";
        StringBuilder out = new StringBuilder();
        for (Object p : parts) {
            if (p instanceof Map<?, ?> m && m.get("plain_text") != null) out.append(m.get("plain_text"));
        }
        return out.toString();
    }

    // A Notion id with or without dashes (or a URL ending in one), as 32 lowercase hex digits.
    static String normalize(Object id) {
        if (id == null) return null;
        String s = String.valueOf(id).trim().toLowerCase(Locale.ROOT);
        String hex = s.replaceAll("[^0-9a-f]", "");
        return hex.length() >= 32 ? hex.substring(hex.length() - 32) : null;
    }

    // GET with the account's token: the object, or null when Notion says it can't find it.
    private Map<?, ?> get(String token, String path) throws TriggerSetupException {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Notion-Version", VERSION)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) return jsonMapper.readValue(response.body(), Map.class);
            if (status == 404 || status == 400) return null;
            if (status == 401) {
                throw new TriggerSetupException("Notion no longer accepts this account. Reconnect it, then turn the workflow on again.");
            }
            throw new TriggerSetupException("Notion had a problem (HTTP " + status + "). Try again.");
        } catch (TriggerSetupException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new TriggerSetupException("Couldn't reach Notion. Try again.");
        }
    }
}
