package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.model.TriggerSubscription;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

// Stripe triggers as webhook endpoints, one per workflow, created with the trigger's API key:
//   trg_stripe_new_payment   payment_intent.succeeded
//   trg_stripe_new_customer  customer.created
// Stripe makes each endpoint's signing secret (whsec_...) and returns it once, on creation; it's
// kept encrypted with the subscription. Events are sent in the API version pinned here, so their
// shape doesn't depend on the account's default. A restricted key needs "Webhook Endpoints: Write".
// Also turns an event into the trigger's data (toTriggerBody), or empty for events it ignores.
@Component
public class StripeTriggers implements AppTriggerRegistrar {

    public static final String NEW_PAYMENT = "trg_stripe_new_payment";
    public static final String NEW_CUSTOMER = "trg_stripe_new_customer";
    static final String API_VERSION = "2024-06-20";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final String apiBase;

    public StripeTriggers(JsonMapper jsonMapper, @Value("${connectors.stripe.api-base:https://api.stripe.com/v1}") String apiBase) {
        this.jsonMapper = jsonMapper;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override
    public boolean supports(String appId) {
        return "app_stripe".equals(appId);
    }

    static String event(String triggerId) {
        return NEW_CUSTOMER.equals(triggerId) ? "customer.created" : "payment_intent.succeeded";
    }

    @Override
    public Registration register(TriggerSubscription s, Map<String, String> credentials, String hookUrl, String secret)
            throws TriggerSetupException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("url", hookUrl);
        form.put("enabled_events[0]", event(s.getTriggerId()));
        form.put("api_version", API_VERSION);
        form.put("description", "Autom8r workflow " + s.getWorkflowId());
        form.put("metadata[autom8r_workflow]", s.getWorkflowId());
        HttpResponse<String> response = call(credentials, "POST", "/webhook_endpoints", form);
        int status = response.statusCode();
        Map<?, ?> body = parse(response.body());
        if (status == 200) {
            return new Registration(String.valueOf(body.get("id")), String.valueOf(body.get("secret")));
        }
        String message = body.get("error") instanceof Map<?, ?> e && e.get("message") != null ? String.valueOf(e.get("message")) : "HTTP " + status;
        throw new TriggerSetupException(switch (status) {
            case 401 -> "Stripe no longer accepts this account's API key. Reconnect it, then turn the workflow on again.";
            case 403 -> "This Stripe key can't create webhooks (" + message + "). Give the restricted key "
                    + "\"Webhook Endpoints: Write\", then turn the workflow on again.";
            case 400 -> "Stripe refused the webhook: " + message;
            default -> "Stripe couldn't add the webhook (HTTP " + status + "). Try again.";
        });
    }

    @Override
    public void unregister(TriggerSubscription s, Map<String, String> credentials) {
        if (s.getExternalId() == null) return;
        try {
            call(credentials, "DELETE", "/webhook_endpoints/" + s.getExternalId(), null);
        } catch (Exception e) {
            // Already gone, or Stripe unreachable: a leftover endpoint's events are rejected anyway
            // (no subscription matches), and the user can delete it under Developers -> Webhooks.
            System.out.println("Couldn't remove Stripe webhook endpoint " + s.getExternalId() + ": " + e.getMessage());
        }
    }

    // The trigger's data for an event, or empty when it isn't the trigger's event.
    public Optional<Map<String, Object>> toTriggerBody(String triggerId, Map<?, ?> event) {
        if (!event(triggerId).equals(event.get("type"))
                || !(event.get("data") instanceof Map<?, ?> data) || !(data.get("object") instanceof Map<?, ?> o)) {
            return Optional.empty();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", o.get("id"));
        if (NEW_CUSTOMER.equals(triggerId)) {
            out.put("name", o.get("name"));
            out.put("email", o.get("email"));
            out.put("phone", o.get("phone"));
            out.put("description", o.get("description"));
        } else {
            out.put("amount", o.get("amount_received") != null ? o.get("amount_received") : o.get("amount"));
            out.put("currency", o.get("currency"));
            out.put("customerId", o.get("customer"));
            out.put("email", o.get("receipt_email"));
            out.put("description", o.get("description"));
            out.put("chargeId", o.get("latest_charge"));
        }
        out.put("metadata", o.get("metadata") instanceof Map<?, ?> m ? m : Map.of());
        out.put("createdAt", o.get("created") instanceof Number n ? Instant.ofEpochSecond(n.longValue()).toString() : null);
        out.put("livemode", event.get("livemode"));
        return Optional.of(out);
    }

    private HttpResponse<String> call(Map<String, String> credentials, String method, String path, Map<String, String> form)
            throws TriggerSetupException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + credentials.get("apiKey"))
                    .header("Stripe-Version", API_VERSION)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .method(method, form == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(form(form)))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new TriggerSetupException("Couldn't reach Stripe to set up the trigger. Try again.");
        }
    }

    private Map<?, ?> parse(String body) {
        try {
            return jsonMapper.readValue(body, Map.class);
        } catch (RuntimeException notJson) {
            return Map.of();
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
