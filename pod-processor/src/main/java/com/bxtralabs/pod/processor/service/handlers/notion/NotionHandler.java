package com.bxtralabs.pod.processor.service.handlers.notion;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Notion actions, through the step's Notion connection (an internal integration secret):
//   notion.create_page  parentId (a database or page, ID or URL), title, properties?, content?
//                       -> {id, url, parent}
//   notion.update_page  pageId, properties -> {id, url, lastEditedTime}
// A database parent takes the title under its title column plus any properties; a page parent
// takes only a title. content becomes paragraphs, one per non-empty line.
// Failures: not found (usually: not shared with the integration), no permission, and input Notion
// rejects fail the step for good; rate limits, conflicts and 5xx are retried.
@Component
@Order(1)
public class NotionHandler implements ActionHandler {

    public static final String CREATE_PAGE = "notion.create_page";
    public static final String UPDATE_PAGE = "notion.update_page";
    static final String VERSION = "2022-06-28";
    private static final Pattern ID = Pattern.compile("([0-9a-fA-F]{8})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{12})");
    private static final int MAX_TEXT = 2000;   // Notion's limit per rich-text item
    private static final int MAX_BLOCKS = 100;  // per request

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final String apiBase;

    public NotionHandler(JsonMapper jsonMapper, @Value("${connectors.notion.api-base:https://api.notion.com/v1}") String apiBase) {
        this.jsonMapper = jsonMapper;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override
    public boolean supports(GraphNode node) {
        return CREATE_PAGE.equals(node.type()) || UPDATE_PAGE.equals(node.type());
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
            throw new PermanentStepException("This step needs a Notion account. Choose one in the step's setup.");
        }
        Map<String, Object> properties = properties(input.get("properties"));

        if (UPDATE_PAGE.equals(node.type())) {
            String pageId = id(input.get("pageId"), "Page ID");
            Map<?, ?> page = send(token, "PATCH", "/pages/" + pageId, Map.of("properties", properties), "page " + pageId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", page.get("id"));
            out.put("url", page.get("url"));
            out.put("lastEditedTime", page.get("last_edited_time"));
            return out;
        }

        String parentId = id(input.get("parentId"), "Parent database or page ID");
        String title = text(input.get("title"));
        Map<String, Object> body = new LinkedHashMap<>();
        Map<?, ?> database = findDatabase(token, parentId);
        if (database != null) {
            Map<String, Object> props = new LinkedHashMap<>(properties);
            props.put(titleColumn(database), Map.of("title", richText(title)));
            body.put("parent", Map.of("database_id", parentId));
            body.put("properties", props);
        } else {
            if (!properties.isEmpty()) {
                throw new PermanentStepException("Properties only apply under a database; " + parentId + " is a page");
            }
            body.put("parent", Map.of("page_id", parentId));
            body.put("properties", Map.of("title", Map.of("title", richText(title))));
        }
        List<Map<String, Object>> blocks = paragraphs(text(input.get("content")));
        if (!blocks.isEmpty()) body.put("children", blocks);

        Map<?, ?> page = send(token, "POST", "/pages", body, (database != null ? "database " : "page ") + parentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", page.get("id"));
        out.put("url", page.get("url"));
        out.put("parent", database != null ? "database" : "page");
        return out;
    }

    // The database, or null if the ID isn't one the integration can see as a database (then
    // it's taken to be a page, and Notion's answer to creating the page says if it isn't).
    private Map<?, ?> findDatabase(String token, String id) throws Exception {
        HttpResponse<String> response = raw(token, "GET", "/databases/" + id, null);
        if (response.statusCode() == 200) {
            return jsonMapper.readValue(response.body(), Map.class);
        }
        if (response.statusCode() == 404 || response.statusCode() == 400) {
            return null; // object_not_found, or validation_error: "is a page, not a database"
        }
        return check(response, "database " + id); // throws for 401, 403, 429, 5xx...
    }

    private static String titleColumn(Map<?, ?> database) {
        if (database.get("properties") instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> e : props.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> p && "title".equals(p.get("type"))) {
                    return String.valueOf(e.getKey());
                }
            }
        }
        return "Name";
    }

    private Map<?, ?> send(String token, String method, String path, Object body, String what) throws Exception {
        return check(raw(token, method, path, body), what);
    }

    private HttpResponse<String> raw(String token, String method, String path, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", VERSION)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)));
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IOException("Couldn't reach Notion: " + e.getMessage(), e);
        }
    }

    private Map<?, ?> check(HttpResponse<String> response, String what) throws Exception {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return jsonMapper.readValue(response.body(), Map.class);
        }
        String code = "", message = "";
        try {
            Map<?, ?> err = jsonMapper.readValue(response.body(), Map.class);
            code = String.valueOf(err.get("code"));
            message = String.valueOf(err.get("message"));
        } catch (RuntimeException notJson) {
            // keep them empty
        }
        if (status == 429 || status == 409 || status >= 500) {
            throw new IllegalStateException("Notion is busy or had a problem (" + (code.isEmpty() ? "HTTP " + status : code) + "); will try again");
        }
        throw new PermanentStepException(switch (status) {
            case 401 -> "Notion no longer accepts this account's secret. Reconnect it on the Connections page.";
            case 403 -> "The Notion integration isn't allowed to do this on " + what + ": " + message;
            case 404 -> "Notion couldn't find " + what + ". Share it with the integration (… → Connections in Notion), then run again.";
            default -> "Notion rejected the request: " + message;
        });
    }

    // A pasted URL or an ID with or without dashes -> the dashed ID Notion expects.
    private static String id(Object value, String label) throws PermanentStepException {
        Matcher m = ID.matcher(text(value));
        String last = null;
        while (m.find()) last = m.group(1) + "-" + m.group(2) + "-" + m.group(3) + "-" + m.group(4) + "-" + m.group(5);
        if (last == null) {
            throw new PermanentStepException(label + " must be a Notion ID or link, got \"" + text(value) + "\"");
        }
        return last.toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Object value) throws PermanentStepException {
        if (value == null || (value instanceof String s && s.isBlank())) return Map.of();
        if (value instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (value instanceof String s) {
            try {
                Object parsed = jsonMapper.readValue(s, Object.class);
                if (parsed instanceof Map<?, ?> m) return (Map<String, Object>) m;
            } catch (RuntimeException ignored) {
                // reported below
            }
        }
        throw new PermanentStepException("Properties must be a JSON object in Notion's format");
    }

    private static List<Map<String, Object>> richText(String text) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += MAX_TEXT) {
            parts.add(Map.of("type", "text", "text", Map.of("content", text.substring(i, Math.min(text.length(), i + MAX_TEXT)))));
        }
        return parts;
    }

    private static List<Map<String, Object>> paragraphs(String content) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (String line : content.split("\\R")) {
            if (line.isBlank() || blocks.size() == MAX_BLOCKS) continue;
            blocks.add(Map.of("object", "block", "type", "paragraph", "paragraph", Map.of("rich_text", richText(line))));
        }
        return blocks;
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
