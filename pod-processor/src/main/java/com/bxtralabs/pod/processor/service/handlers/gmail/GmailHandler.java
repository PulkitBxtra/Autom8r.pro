package com.bxtralabs.pod.processor.service.handlers.gmail;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.AccountRejectedException;
import com.bxtralabs.pod.processor.service.handlers.ActionHandler;
import com.bxtralabs.pod.processor.service.handlers.AppCalls;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import com.bxtralabs.pod.processor.service.handlers.StepContext;
import com.bxtralabs.pod.processor.service.handlers.StepCredentials;
import com.bxtralabs.pod.processor.service.handlers.UncertainStepException;
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
import java.util.*;
import java.util.regex.Pattern;

// Gmail actions, through the step's Gmail connection (Google OAuth, gmail.compose):
//   gmail.send_email    to, cc?, subject, body, bodyType? (text|html) -> {id, threadId, to, subject}
//   gmail.create_draft  to?, subject, body                            -> {draftId, id, threadId, to, subject}
// The email is sent from the connected account. Each one carries a Message-ID made from the step
// run, so an earlier attempt's email can be recognised. Gmail has no idempotency key: if an earlier
// attempt may have sent it, the account is searched for that Message-ID first, which needs read
// access gmail.compose doesn't give. Without it the step stops rather than risk sending twice.
// Recipients and the subject can't carry line breaks (no injected headers). Errors the user must fix
// (bad address, missing permission) fail the step for good; rate limits and 5xx are retried.
@Component
@Order(1)
public class GmailHandler implements ActionHandler {

    public static final String SEND = "gmail.send_email";
    public static final String DRAFT = "gmail.create_draft";
    // Loose on purpose: Gmail itself is the judge; this only catches what clearly isn't an address.
    private static final Pattern ADDRESS = Pattern.compile("(?:[^<>,]*<)?[^@\\s<>,]+@[^@\\s<>,]+\\.[^@\\s<>,]+>?");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final String apiBase;

    public GmailHandler(JsonMapper jsonMapper,
                        @Value("${connectors.gmail.api-base:https://gmail.googleapis.com/gmail/v1}") String apiBase) {
        this.jsonMapper = jsonMapper;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override
    public boolean supports(GraphNode node) {
        return SEND.equals(node.type()) || DRAFT.equals(node.type());
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
        String token = credentials == null ? null : credentials.get("access_token");
        if (token == null || token.isBlank()) {
            throw new PermanentStepException("This step needs a Gmail account. Choose one in the step's setup.");
        }
        boolean send = SEND.equals(node.type());
        List<String> to = addresses(text(input.get("to")), "To", send);
        List<String> cc = send ? addresses(text(input.get("cc")), "Cc", false) : List.of();
        String subject = oneLine(text(input.get("subject")), "Subject");
        if (subject.isEmpty()) {
            throw new PermanentStepException("The email needs a subject");
        }
        String body = text(input.get("body"));
        boolean html = "html".equalsIgnoreCase(text(input.get("bodyType")));
        String messageId = context == null ? null : "<" + context.marker().replace(':', '-') + "@autom8r.pro>";

        if (context != null && context.mayHaveHappened()) {
            Map<?, ?> earlier = earlier(token, messageId, send);
            if (earlier != null) {
                Map<String, Object> out = output(earlier, to, subject);
                out.put("alreadyDone", true);
                return out;
            }
        }

        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mime(to, cc, subject, body, html, messageId).getBytes(StandardCharsets.UTF_8));
        if (send) {
            Map<?, ?> sent = post(token, "/users/me/messages/send", Map.of("raw", raw), "the email");
            return output(sent, to, subject);
        }
        Map<?, ?> draft = post(token, "/users/me/drafts", Map.of("message", Map.of("raw", raw)), "the draft");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("draftId", String.valueOf(draft.get("id")));
        out.putAll(output(draft.get("message") instanceof Map<?, ?> m ? m : Map.of(), to, subject));
        return out;
    }

    private static Map<String, Object> output(Map<?, ?> message, List<String> to, String subject) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", message.get("id") == null ? null : String.valueOf(message.get("id")));
        out.put("threadId", message.get("threadId") == null ? null : String.valueOf(message.get("threadId")));
        out.put("to", String.join(", ", to));
        out.put("subject", subject);
        return out;
    }

    // The RFC 5322 message: UTF-8 throughout, headers encoded where they aren't plain ASCII, the
    // body base64 so any characters and line lengths survive.
    static String mime(List<String> to, List<String> cc, String subject, String body, boolean html, String messageId) {
        StringBuilder m = new StringBuilder();
        if (!to.isEmpty()) m.append("To: ").append(String.join(", ", to)).append("\r\n");
        if (!cc.isEmpty()) m.append("Cc: ").append(String.join(", ", cc)).append("\r\n");
        m.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        if (messageId != null) m.append("Message-ID: ").append(messageId).append("\r\n");
        m.append("MIME-Version: 1.0\r\n");
        m.append("Content-Type: ").append(html ? "text/html" : "text/plain").append("; charset=UTF-8\r\n");
        m.append("Content-Transfer-Encoding: base64\r\n\r\n");
        String encoded = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < encoded.length(); i += 76) {
            m.append(encoded, i, Math.min(encoded.length(), i + 76)).append("\r\n");
        }
        return m.toString();
    }

    static String encodeHeader(String value) {
        return StandardCharsets.US_ASCII.newEncoder().canEncode(value) ? value
                : "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static List<String> addresses(String value, String label, boolean required) throws PermanentStepException {
        oneLine(value, label);
        List<String> out = new ArrayList<>();
        for (String part : value.split("[,;]")) {
            String a = part.trim();
            if (a.isEmpty()) continue;
            if (!ADDRESS.matcher(a).matches()) {
                throw new PermanentStepException(label + ": \"" + a + "\" isn't an email address");
            }
            out.add(a);
        }
        if (required && out.isEmpty()) {
            throw new PermanentStepException("The email needs at least one address in To");
        }
        return out;
    }

    private static String oneLine(String value, String label) throws PermanentStepException {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new PermanentStepException(label + " can't contain line breaks");
        }
        return value;
    }

    // The email (or draft's message) an earlier attempt made, found by its Message-ID.
    private Map<?, ?> earlier(String token, String messageId, boolean send) throws Exception {
        String q = URLEncoder.encode("rfc822msgid:" + messageId + (send ? "" : " in:drafts"), StandardCharsets.UTF_8);
        Map<?, ?> found;
        try {
            found = send(HttpRequest.newBuilder(URI.create(apiBase + "/users/me/messages?includeSpamTrash=true&q=" + q)).GET(),
                    token, "the account's mail", false);
        } catch (AccountRejectedException e) {
            throw e;
        } catch (PermanentStepException e) {
            throw new PermanentStepException("An earlier try may already have " + (send ? "sent this email" : "made this draft")
                    + ", and Autom8r can't check (" + e.getMessage() + "). Not " + (send ? "sending" : "making it")
                    + " again to avoid a duplicate: look in Gmail's " + (send ? "Sent" : "Drafts") + " folder.");
        }
        if (!(found.get("messages") instanceof List<?> list) || list.isEmpty() || !(list.getFirst() instanceof Map<?, ?> first)) {
            return null;
        }
        return first;
    }

    private Map<?, ?> post(String token, String path, Object body, String what) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(apiBase + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body))), token, what, true);
    }

    private Map<?, ?> send(HttpRequest.Builder request, String token, String what, boolean creates) throws Exception {
        HttpResponse<String> response = AppCalls.send(http, request.timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).build(), "Gmail", creates);
        int status = response.statusCode();
        if (status == 429) {
            throw new IllegalStateException("Gmail rate limit reached; will try again");
        }
        if (status >= 500) {
            if (creates) throw AppCalls.uncertainServerError("Gmail", status, what);
            throw new IllegalStateException("Gmail had a problem (HTTP " + status + "); will try again");
        }
        Map<?, ?> body;
        try {
            body = response.body() == null || response.body().isBlank() ? Map.of() : jsonMapper.readValue(response.body(), Map.class);
        } catch (RuntimeException notJson) {
            throw new IllegalStateException("Gmail answered HTTP " + status + " with something unexpected; will try again");
        }
        if (status >= 200 && status < 300) {
            return body;
        }
        Map<?, ?> error = body.get("error") instanceof Map<?, ?> e ? e : Map.of();
        String message = error.get("message") == null ? "HTTP " + status : String.valueOf(error.get("message"));
        String reason = reason(error);
        if (status == 401) {
            throw new AccountRejectedException("Google no longer accepts this Gmail account (" + message + "). Reconnect it.");
        }
        if (status == 403 && ("rateLimitExceeded".equals(reason) || "userRateLimitExceeded".equals(reason))) {
            throw new IllegalStateException("Gmail rate limit reached; will try again");
        }
        if (status == 403) {
            throw new PermanentStepException("Google didn't allow this (" + message + "). Reconnect the Gmail account and "
                    + "allow everything it asks for.");
        }
        throw new PermanentStepException("Gmail refused " + what + ": " + message);
    }

    private static String reason(Map<?, ?> error) {
        if (error.get("errors") instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> first
                && first.get("reason") != null) {
            return String.valueOf(first.get("reason"));
        }
        return "";
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
