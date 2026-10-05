package com.bxtralabs.pod.connector.triggers;

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

// Gmail triggers, polled (TriggerPoller) through the mailbox's history:
//   trg_gmail_new_email       a message arrives in the inbox
//   trg_gmail_new_attachment  ...one with attachments
//   trg_gmail_new_label       a message gets a label (config "label": its name), also on arrival
// Turning one on records the mailbox's current historyId; each poll asks for what changed since,
// then reads each new message. If Gmail has forgotten that far back (404, about a week), polling
// restarts from now. Needs read access (gmail.readonly), which Gmail connections made before
// triggers existed don't have: those are told to reconnect.
@Component
public class GmailTriggers implements PollingTriggers {

    public static final String NEW_EMAIL = "trg_gmail_new_email";
    public static final String NEW_ATTACHMENT = "trg_gmail_new_attachment";
    public static final String NEW_LABEL = "trg_gmail_new_label";
    static final String READ_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    // At most this many messages per poll: after a bigger burst (an import, a mailing-list flood)
    // only the newest start runs, rather than the trigger falling ever further behind.
    static final int MAX_MESSAGES = 100;
    static final int MAX_TEXT = 20_000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final ConnectionRepository connections;
    private final String apiBase;

    public GmailTriggers(JsonMapper jsonMapper, ConnectionRepository connections,
                         @Value("${connectors.gmail.api-base:https://gmail.googleapis.com/gmail/v1}") String apiBase) {
        this.jsonMapper = jsonMapper;
        this.connections = connections;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override
    public boolean supports(String appId) {
        return "app_gmail".equals(appId);
    }

    @Override
    public Registration register(TriggerSubscription s, Map<String, String> credentials, String hookUrl, String unused)
            throws TriggerSetupException {
        Connection c = connections.findById(s.getConnectionId())
                .orElseThrow(() -> new TriggerSetupException("The trigger's account no longer exists; choose another"));
        if (c.getScopes() == null || !c.getScopes().contains(READ_SCOPE)) {
            throw new TriggerSetupException("Gmail triggers need to read your mail, which this Gmail account wasn't allowed to. "
                    + "Reconnect it (Connections -> the account -> Reconnect) and allow reading email, then turn the workflow on again.");
        }
        String token = credentials.get("access_token");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("historyId", String.valueOf(get(token, "/users/me/profile").get("historyId")));
        if (NEW_LABEL.equals(s.getTriggerId())) {
            String name = s.getConfig() == null || s.getConfig().get("label") == null ? "" : String.valueOf(s.getConfig().get("label")).trim();
            Map<?, ?> label = findLabel(token, name);
            meta.put("labelId", label.get("id"));
            meta.put("labelName", label.get("name"));
        }
        s.setMeta(meta);
        return new Registration(null, null);
    }

    private Map<?, ?> findLabel(String token, String name) throws TriggerSetupException {
        if (name.isEmpty()) throw new TriggerSetupException("Choose the Gmail label to watch");
        List<?> labels = get(token, "/users/me/labels").get("labels") instanceof List<?> l ? l : List.of();
        for (Object o : labels) {
            if (o instanceof Map<?, ?> m && name.equalsIgnoreCase(String.valueOf(m.get("name")))) return m;
        }
        throw new TriggerSetupException("This Gmail account has no label \"" + name + "\"");
    }

    @Override
    public Poll poll(TriggerSubscription s, Map<String, String> credentials) throws TriggerSetupException {
        String token = credentials.get("access_token");
        Map<String, Object> meta = s.getMeta() == null ? Map.of() : s.getMeta();
        String since = String.valueOf(meta.get("historyId"));
        String labelId = meta.get("labelId") == null ? null : String.valueOf(meta.get("labelId"));
        boolean labelTrigger = NEW_LABEL.equals(s.getTriggerId());

        // Message ids in the order they changed, without repeats.
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String latest = since;
        String page = null;
        for (int pages = 0; pages < 5; pages++) {
            String path = "/users/me/history?startHistoryId=" + enc(since) + "&historyTypes=messageAdded"
                    + (labelTrigger ? "&historyTypes=labelAdded" : "") + "&maxResults=100" + (page == null ? "" : "&pageToken=" + enc(page));
            Map<?, ?> history = getOrNull(token, path);
            if (history == null) {
                // Too far back for Gmail: start again from now.
                return new Poll(List.of(), Map.of("historyId", String.valueOf(get(token, "/users/me/profile").get("historyId"))));
            }
            if (history.get("historyId") != null) latest = String.valueOf(history.get("historyId"));
            if (history.get("history") instanceof List<?> records) {
                for (Object r : records) {
                    if (!(r instanceof Map<?, ?> record)) continue;
                    collect(record.get("messagesAdded"), labelTrigger ? labelId : "INBOX", ids);
                    if (labelTrigger) collect(record.get("labelsAdded"), labelId, ids);
                }
            }
            page = history.get("nextPageToken") == null ? null : String.valueOf(history.get("nextPageToken"));
            if (page == null) break;
        }

        List<String> newest = new ArrayList<>(ids);
        if (newest.size() > MAX_MESSAGES) newest = newest.subList(newest.size() - MAX_MESSAGES, newest.size());
        List<Event> events = new ArrayList<>();
        for (String id : newest) {
            Map<?, ?> message = getOrNull(token, "/users/me/messages/" + enc(id) + "?format=full");
            if (message == null) continue; // deleted since
            Map<String, Object> body = toTriggerBody(message);
            if (NEW_ATTACHMENT.equals(s.getTriggerId()) && ((List<?>) body.get("attachments")).isEmpty()) continue;
            events.add(new Event("gmail:" + id + (labelTrigger ? ":" + labelId : ""), body));
        }
        return new Poll(events, Map.of("historyId", latest));
    }

    // Adds the ids of messages in a history record's list that have the label.
    private static void collect(Object list, String labelId, Set<String> ids) {
        if (!(list instanceof List<?> items)) return;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> i) || !(i.get("message") instanceof Map<?, ?> m)) continue;
            List<?> labels = m.get("labelIds") instanceof List<?> l ? l : List.of();
            List<?> added = i.get("labelIds") instanceof List<?> l ? l : labels; // labelsAdded lists what was added
            if (labelId != null && added.contains(labelId)) ids.add(String.valueOf(m.get("id")));
        }
    }

    // The trigger's data for a message: who, what, when, its text and attachments' names.
    static Map<String, Object> toTriggerBody(Map<?, ?> message) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<?, ?> payload = message.get("payload") instanceof Map<?, ?> p ? p : Map.of();
        if (payload.get("headers") instanceof List<?> hs) {
            for (Object h : hs) {
                if (h instanceof Map<?, ?> m) headers.putIfAbsent(String.valueOf(m.get("name")).toLowerCase(Locale.ROOT), String.valueOf(m.get("value")));
            }
        }
        StringBuilder plain = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<Map<String, Object>> attachments = new ArrayList<>();
        walk(payload, plain, html, attachments);
        String text = !plain.isEmpty() ? plain.toString()
                : html.toString().replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "").replaceAll("<[^>]+>", " ").replaceAll("[ \\t]+", " ").trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", message.get("id"));
        out.put("threadId", message.get("threadId"));
        out.put("from", headers.get("from"));
        out.put("to", headers.get("to"));
        out.put("cc", headers.get("cc"));
        out.put("subject", headers.get("subject"));
        out.put("date", headers.get("date"));
        out.put("snippet", message.get("snippet"));
        out.put("text", text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
        out.put("labels", message.get("labelIds") instanceof List<?> l ? l : List.of());
        out.put("attachments", attachments);
        return out;
    }

    private static void walk(Map<?, ?> part, StringBuilder plain, StringBuilder html, List<Map<String, Object>> attachments) {
        String mime = String.valueOf(part.get("mimeType"));
        Map<?, ?> body = part.get("body") instanceof Map<?, ?> b ? b : Map.of();
        String filename = part.get("filename") == null ? "" : String.valueOf(part.get("filename"));
        if (!filename.isEmpty()) {
            attachments.add(Map.of("filename", filename, "mimeType", mime, "size", body.get("size") == null ? 0 : body.get("size")));
        } else if (body.get("data") != null && (mime.equals("text/plain") || mime.equals("text/html"))) {
            String decoded = new String(Base64.getUrlDecoder().decode(String.valueOf(body.get("data"))), StandardCharsets.UTF_8);
            (mime.equals("text/plain") ? plain : html).append(decoded);
        }
        if (part.get("parts") instanceof List<?> parts) {
            for (Object p : parts) {
                if (p instanceof Map<?, ?> m) walk(m, plain, html, attachments);
            }
        }
    }

    private Map<?, ?> get(String token, String path) throws TriggerSetupException {
        Map<?, ?> found = getOrNull(token, path);
        if (found == null) throw new TriggerSetupException("Gmail couldn't find that (" + path.replaceAll("\\?.*", "") + ")");
        return found;
    }

    // The JSON answer, or null for 404.
    private Map<?, ?> getOrNull(String token, String path) throws TriggerSetupException {
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(apiBase + path)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new TriggerSetupException("Couldn't reach Gmail. Will try again.");
        }
        int status = response.statusCode();
        if (status == 200) return jsonMapper.readValue(response.body(), Map.class);
        if (status == 404) return null;
        if (status == 401) throw new TriggerSetupException("Google no longer accepts this Gmail account. Reconnect it.");
        if (status == 403) {
            throw new TriggerSetupException("Google didn't allow reading this mailbox. Reconnect the Gmail account and allow reading email.");
        }
        throw new TriggerSetupException("Gmail had a problem (HTTP " + status + "). Will try again.");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
