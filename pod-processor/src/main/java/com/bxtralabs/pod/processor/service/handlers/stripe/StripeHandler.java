package com.bxtralabs.pod.processor.service.handlers.stripe;

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

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// Stripe actions, through the step's Stripe connection (a secret or restricted API key):
//   stripe.create_invoice  customerId, amount (smallest unit), currency, description?, daysUntilDue?, send?
//                          -> {id, number, status, url, amountDue, currency, customerId}
//                          A draft invoice with one line item; with send, finalized and emailed.
//   stripe.refund_payment  paymentIntentId (pi_... or a charge ch_...), amount?, reason?
//                          -> {id, status, amount, currency, paymentIntentId}
// Not twice: every call that changes something carries an Idempotency-Key made from the step run,
// so Stripe answers a repeat with the first result instead of doing it again (keys last 24 hours).
// A lost answer is therefore simply retried. A 5xx is not: Stripe saves that answer under the key
// too and would repeat it, and the call may have gone through, so the step stops and says to check.
// The API version is pinned, so the shapes used here don't depend on the account's default.
// Errors the user must fix (unknown customer, already refunded, key without permission...) fail
// the step for good with Stripe's message; rate limits, and a key still in use by an earlier try
// (409), are retried.
@Component
@Order(1)
public class StripeHandler implements ActionHandler {

    public static final String CREATE_INVOICE = "stripe.create_invoice";
    public static final String REFUND_PAYMENT = "stripe.refund_payment";
    static final String API_VERSION = "2024-06-20";
    private static final Pattern CUSTOMER = Pattern.compile("cus_[A-Za-z0-9]+");
    private static final Pattern PAYMENT = Pattern.compile("(pi|ch|py)_[A-Za-z0-9]+");
    private static final Pattern CURRENCY = Pattern.compile("[a-z]{3}");
    private static final Set<String> REASONS = Set.of("requested_by_customer", "duplicate", "fraudulent");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final String apiBase;

    public StripeHandler(JsonMapper jsonMapper, @Value("${connectors.stripe.api-base:https://api.stripe.com/v1}") String apiBase) {
        this.jsonMapper = jsonMapper;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override
    public boolean supports(GraphNode node) {
        return CREATE_INVOICE.equals(node.type()) || REFUND_PAYMENT.equals(node.type());
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
        String key = credentials == null ? null : credentials.get("apiKey");
        if (key == null || key.isBlank()) {
            throw new PermanentStepException("This step needs a Stripe account. Choose one in the step's setup.");
        }
        // Steps always run with a context; a random key just keeps the calls valid without one.
        String idempotency = context == null ? "autom8r-" + java.util.UUID.randomUUID() : context.marker();
        return REFUND_PAYMENT.equals(node.type())
                ? refund(key, input, idempotency)
                : invoice(key, input, idempotency);
    }

    private Map<String, Object> invoice(String key, Map<String, Object> input, String idempotency) throws Exception {
        String customer = text(input.get("customerId"));
        if (!CUSTOMER.matcher(customer).matches()) {
            throw new PermanentStepException(customer.isEmpty() ? "Choose a Stripe customer"
                    : "\"" + abbreviate(customer) + "\" isn't a Stripe customer ID (cus_...)");
        }
        long amount = amount(input.get("amount"), "Amount", true);
        String currency = text(input.get("currency")).toLowerCase(Locale.ROOT);
        if (!CURRENCY.matcher(currency).matches()) {
            throw new PermanentStepException("Currency must be a three-letter code like usd or eur, not \"" + abbreviate(currency) + "\"");
        }
        long days = input.get("daysUntilDue") == null || text(input.get("daysUntilDue")).isEmpty() ? 30
                : amount(input.get("daysUntilDue"), "Days until due", false);
        boolean send = Boolean.TRUE.equals(input.get("send")) || "true".equalsIgnoreCase(text(input.get("send")));
        String description = text(input.get("description"));

        // A draft that only takes the item added to it below, not the customer's other pending items,
        // in the item's currency (otherwise Stripe uses the customer's default and refuses an item
        // in any other).
        Map<String, String> draft = new LinkedHashMap<>();
        draft.put("customer", customer);
        draft.put("currency", currency);
        draft.put("collection_method", "send_invoice");
        draft.put("days_until_due", String.valueOf(days));
        draft.put("pending_invoice_items_behavior", "exclude");
        draft.put("auto_advance", "false");
        if (!description.isEmpty()) draft.put("description", description);
        Map<?, ?> invoice = post(key, "/invoices", draft, idempotency + ":invoice", "customer " + customer);
        String invoiceId = String.valueOf(invoice.get("id"));

        Map<String, String> item = new LinkedHashMap<>();
        item.put("customer", customer);
        item.put("invoice", invoiceId);
        item.put("amount", String.valueOf(amount));
        item.put("currency", currency);
        item.put("description", description.isEmpty() ? "Invoice item" : description);
        post(key, "/invoiceitems", item, idempotency + ":item", "invoice " + invoiceId);

        if (send) {
            post(key, "/invoices/" + invoiceId + "/finalize", Map.of(), idempotency + ":finalize", "invoice " + invoiceId);
            invoice = post(key, "/invoices/" + invoiceId + "/send", Map.of(), idempotency + ":send", "invoice " + invoiceId);
        } else {
            invoice = get(key, "/invoices/" + invoiceId, "invoice " + invoiceId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", invoiceId);
        out.put("number", invoice.get("number") == null ? null : String.valueOf(invoice.get("number")));
        out.put("status", String.valueOf(invoice.get("status")));
        out.put("url", invoice.get("hosted_invoice_url") == null ? null : String.valueOf(invoice.get("hosted_invoice_url")));
        out.put("amountDue", invoice.get("amount_due"));
        out.put("currency", invoice.get("currency") == null ? currency : String.valueOf(invoice.get("currency")));
        out.put("customerId", customer);
        return out;
    }

    private Map<String, Object> refund(String key, Map<String, Object> input, String idempotency) throws Exception {
        String payment = text(input.get("paymentIntentId"));
        if (!PAYMENT.matcher(payment).matches()) {
            throw new PermanentStepException(payment.isEmpty() ? "Which payment? Set Payment ID"
                    : "\"" + abbreviate(payment) + "\" isn't a Stripe payment ID (pi_... or ch_...)");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put(payment.startsWith("pi_") ? "payment_intent" : "charge", payment);
        if (input.get("amount") != null && !text(input.get("amount")).isEmpty()) {
            params.put("amount", String.valueOf(amount(input.get("amount"), "Amount", true)));
        }
        String reason = text(input.get("reason"));
        if (!reason.isEmpty()) {
            if (!REASONS.contains(reason)) {
                throw new PermanentStepException("Reason must be requested_by_customer, duplicate or fraudulent");
            }
            params.put("reason", reason);
        }
        Map<?, ?> refund = post(key, "/refunds", params, idempotency + ":refund", "payment " + payment);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", String.valueOf(refund.get("id")));
        out.put("status", String.valueOf(refund.get("status")));
        out.put("amount", refund.get("amount"));
        out.put("currency", refund.get("currency") == null ? null : String.valueOf(refund.get("currency")));
        out.put("paymentIntentId", refund.get("payment_intent") == null ? null : String.valueOf(refund.get("payment_intent")));
        return out;
    }

    // A whole number above zero (or zero and up, for days): an amount in the smallest unit.
    private static long amount(Object value, String label, boolean positive) throws PermanentStepException {
        try {
            BigDecimal n = new BigDecimal(text(value));
            if (n.scale() > 0 && n.stripTrailingZeros().scale() > 0) {
                throw new PermanentStepException(label + " must be a whole number" + (label.equals("Amount")
                        ? " in the currency's smallest unit (e.g. cents: 1500 = $15.00), not " + text(value) : ", not " + text(value)));
            }
            long v = n.longValueExact();
            if (positive ? v <= 0 : v < 0) {
                throw new PermanentStepException(label + " must be " + (positive ? "more than 0" : "0 or more") + ", not " + v);
            }
            return v;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new PermanentStepException(label + " must be a whole number, not \"" + abbreviate(text(value)) + "\"");
        }
    }

    private Map<?, ?> post(String key, String path, Map<String, String> params, String idempotencyKey, String what) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(apiBase + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(form(params))), key, what);
    }

    private Map<?, ?> get(String key, String path, String what) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(apiBase + path)).GET(), key, what);
    }

    // Every change carries an idempotency key, so no call here can do anything twice: a lost
    // answer is an ordinary retry (creates=false).
    private Map<?, ?> send(HttpRequest.Builder request, String key, String what) throws Exception {
        HttpResponse<String> response = AppCalls.send(http, request.timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + key)
                .header("Stripe-Version", API_VERSION)
                .build(), "Stripe", false);
        int status = response.statusCode();
        if (status == 429) {
            throw new IllegalStateException("Stripe rate limit reached; will try again");
        }
        if (status == 409) {
            throw new IllegalStateException("Stripe is still working on this step's earlier try; will try again");
        }
        if (status >= 500) {
            throw new PermanentStepException("Stripe had an internal problem (HTTP " + status + ") with " + what
                    + ", and it may or may not have gone through. Check in the Stripe dashboard before running this again.");
        }
        Map<?, ?> body;
        try {
            body = jsonMapper.readValue(response.body(), Map.class);
        } catch (RuntimeException notJson) {
            throw new IllegalStateException("Stripe answered HTTP " + status + " with something unexpected; will try again");
        }
        if (status >= 200 && status < 300) {
            return body;
        }
        Map<?, ?> error = body.get("error") instanceof Map<?, ?> e ? e : Map.of();
        String message = error.get("message") == null ? "HTTP " + status : String.valueOf(error.get("message"));
        String code = error.get("code") == null ? "" : String.valueOf(error.get("code"));
        String type = error.get("type") == null ? "" : String.valueOf(error.get("type"));
        if (status == 401) {
            throw new AccountRejectedException("Stripe no longer accepts this account's API key (" + message
                    + "). Make a new key and reconnect the account.");
        }
        if (status == 403) {
            throw new PermanentStepException("This Stripe key isn't allowed to do that (" + message
                    + "). Give the restricted key that permission, or use another key.");
        }
        if ("idempotency_error".equals(type)) {
            throw new PermanentStepException("Stripe saw this step's earlier try with different settings, so it won't "
                    + "repeat it with these (" + message + ")");
        }
        if ("resource_missing".equals(code)) {
            throw new PermanentStepException("Stripe couldn't find " + what + " (" + message + ")");
        }
        throw new PermanentStepException("Stripe refused the request: " + message);
    }

    private static String form(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        params.forEach((k, v) -> {
            if (!out.isEmpty()) out.append('&');
            out.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
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
