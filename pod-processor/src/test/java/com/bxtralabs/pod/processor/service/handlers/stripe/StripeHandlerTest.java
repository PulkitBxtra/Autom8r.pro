package com.bxtralabs.pod.processor.service.handlers.stripe;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.AccountRejectedException;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import com.bxtralabs.pod.processor.service.handlers.StepContext;
import com.bxtralabs.pod.processor.service.handlers.StepCredentials;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StripeHandlerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private StripeHandler handler;
    // "METHOD path body | Idempotency-Key | Stripe-Version | Authorization" per call, in order.
    private final List<String> calls = new ArrayList<>();
    // "METHOD path" -> "status:body"
    private final Map<String, String> answers = new HashMap<>();

    private static final StepCredentials KEY = new StepCredentials("con_1", "app_stripe", "TOKEN", Map.of("apiKey", "sk_test_1"));
    private static final StepContext RUN = new StepContext("str_abc", 1, 0, false);
    private static final String DRAFT = "{\"id\":\"in_1\",\"status\":\"draft\",\"number\":null,\"amount_due\":1500,\"currency\":\"usd\","
            + "\"hosted_invoice_url\":null}";
    private static final String SENT = "{\"id\":\"in_1\",\"status\":\"open\",\"number\":\"ABC-0001\",\"amount_due\":1500,\"currency\":\"usd\","
            + "\"hosted_invoice_url\":\"https://invoice.stripe.com/i/x\"}";

    @BeforeEach
    void start() throws IOException {
        answers.put("POST /invoices", "200:" + DRAFT);
        answers.put("POST /invoiceitems", "200:{\"id\":\"ii_1\"}");
        answers.put("GET /invoices/in_1", "200:" + DRAFT);
        answers.put("POST /invoices/in_1/finalize", "200:" + SENT);
        answers.put("POST /invoices/in_1/send", "200:" + SENT);
        answers.put("POST /refunds", "200:{\"id\":\"re_1\",\"status\":\"succeeded\",\"amount\":500,\"currency\":\"usd\",\"payment_intent\":\"pi_9\"}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            calls.add(key + " " + body + " | " + ex.getRequestHeaders().getFirst("Idempotency-Key") + " | "
                    + ex.getRequestHeaders().getFirst("Stripe-Version") + " | " + ex.getRequestHeaders().getFirst("Authorization"));
            String answer = answers.getOrDefault(key, "404:{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Unrecognized request URL\"}}");
            respond(ex, Integer.parseInt(answer.substring(0, 3)), answer.substring(4));
        });
        server.start();
        handler = new StripeHandler(json, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static GraphNode node(String type) {
        return new GraphNode("a", "action", "Stripe", "item", "Step", type, Map.of(), null, "app_stripe", "con_1", null);
    }

    private Map<String, Object> invoice(Map<String, Object> input) throws Exception {
        return handler.execute(node(StripeHandler.CREATE_INVOICE), input, KEY, RUN);
    }

    private Map<String, Object> refund(Map<String, Object> input) throws Exception {
        return handler.execute(node(StripeHandler.REFUND_PAYMENT), input, KEY, RUN);
    }

    @Test
    void aDraftInvoiceTakesOnlyItsOwnLineItem() throws Exception {
        Map<String, Object> out = invoice(Map.of("customerId", "cus_1", "amount", 1500, "currency", "USD", "description", "Consulting"));

        assertEquals(3, calls.size(), calls.toString());
        assertTrue(calls.get(0).startsWith("POST /invoices customer=cus_1&currency=usd&collection_method=send_invoice&days_until_due=30"
                + "&pending_invoice_items_behavior=exclude&auto_advance=false&description=Consulting | autom8r-step:str_abc:invoice | "
                + StripeHandler.API_VERSION + " | Bearer sk_test_1"), calls.get(0));
        assertTrue(calls.get(1).startsWith("POST /invoiceitems customer=cus_1&invoice=in_1&amount=1500&currency=usd&description=Consulting"
                + " | autom8r-step:str_abc:item"), calls.get(1));
        assertTrue(calls.get(2).startsWith("GET /invoices/in_1 "), calls.get(2));
        assertEquals("draft", out.get("status"));
        assertEquals("in_1", out.get("id"));
        assertEquals(1500, out.get("amountDue"));
    }

    @Test
    void sendingFinalizesAndEmailsIt() throws Exception {
        Map<String, Object> out = invoice(Map.of("customerId", "cus_1", "amount", "1500", "currency", "usd", "send", true, "daysUntilDue", 7));

        assertTrue(calls.get(0).contains("days_until_due=7"));
        assertTrue(calls.get(2).startsWith("POST /invoices/in_1/finalize  | autom8r-step:str_abc:finalize"), calls.get(2));
        assertTrue(calls.get(3).startsWith("POST /invoices/in_1/send  | autom8r-step:str_abc:send"), calls.get(3));
        assertEquals(Map.of("id", "in_1", "number", "ABC-0001", "status", "open", "url", "https://invoice.stripe.com/i/x",
                "amountDue", 1500, "currency", "usd", "customerId", "cus_1"), out);
    }

    @Test
    void everyRetryUsesTheSameKeysSoStripeDoesNothingTwice() throws Exception {
        invoice(Map.of("customerId", "cus_1", "amount", 1500, "currency", "usd"));
        handler.execute(node(StripeHandler.CREATE_INVOICE), Map.of("customerId", "cus_1", "amount", 1500, "currency", "usd"), KEY,
                new StepContext("str_abc", 2, 0, true));
        List<String> keys = calls.stream().map(c -> c.split(" \\| ")[1]).toList();
        assertEquals(keys.subList(0, 3), keys.subList(3, 6));
    }

    @Test
    void refundsAPaymentOrAChargeInPartOrInFull() throws Exception {
        Map<String, Object> out = refund(Map.of("paymentIntentId", "pi_9", "amount", 500, "reason", "duplicate"));
        assertTrue(calls.getFirst().startsWith("POST /refunds payment_intent=pi_9&amount=500&reason=duplicate | autom8r-step:str_abc:refund"),
                calls.getFirst());
        assertEquals(Map.of("id", "re_1", "status", "succeeded", "amount", 500, "currency", "usd", "paymentIntentId", "pi_9"), out);

        refund(Map.of("paymentIntentId", "ch_7", "amount", ""));
        assertTrue(calls.getLast().startsWith("POST /refunds charge=ch_7 | "), calls.getLast());
    }

    @Test
    void settingsStripeWouldRefuseFailBeforeCallingIt() {
        assertTrue(assertThrows(PermanentStepException.class, () -> invoice(Map.of("customerId", "ada@example.com", "amount", 1, "currency", "usd")))
                .getMessage().contains("isn't a Stripe customer ID"));
        assertTrue(assertThrows(PermanentStepException.class, () -> invoice(Map.of("customerId", "cus_1", "amount", "15.50", "currency", "usd")))
                .getMessage().contains("smallest unit"));
        assertTrue(assertThrows(PermanentStepException.class, () -> invoice(Map.of("customerId", "cus_1", "amount", 0, "currency", "usd")))
                .getMessage().contains("more than 0"));
        assertTrue(assertThrows(PermanentStepException.class, () -> invoice(Map.of("customerId", "cus_1", "amount", 5, "currency", "dollars")))
                .getMessage().startsWith("Currency must be"));
        assertTrue(assertThrows(PermanentStepException.class, () -> refund(Map.of("paymentIntentId", "order-42")))
                .getMessage().contains("isn't a Stripe payment ID"));
        assertThrows(PermanentStepException.class, () -> handler.execute(node(StripeHandler.REFUND_PAYMENT),
                Map.of("paymentIntentId", "pi_9"), null, RUN), "no account");
        assertTrue(calls.isEmpty());
    }

    @Test
    void stripesReasonsAreKept() {
        answers.put("POST /refunds", "400:{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"charge_already_refunded\","
                + "\"message\":\"Charge ch_7 has already been refunded.\"}}");
        assertEquals("Stripe refused the request: Charge ch_7 has already been refunded.",
                assertThrows(PermanentStepException.class, () -> refund(Map.of("paymentIntentId", "ch_7"))).getMessage());
        answers.put("POST /invoices", "400:{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\","
                + "\"message\":\"No such customer: 'cus_gone'\"}}");
        assertTrue(assertThrows(PermanentStepException.class, () -> invoice(Map.of("customerId", "cus_gone", "amount", 1, "currency", "usd")))
                .getMessage().startsWith("Stripe couldn't find customer cus_gone"));
        answers.put("POST /refunds", "400:{\"error\":{\"type\":\"idempotency_error\",\"message\":\"Keys for idempotent requests can only be used with the same parameters\"}}");
        assertTrue(assertThrows(PermanentStepException.class, () -> refund(Map.of("paymentIntentId", "pi_9")))
                .getMessage().contains("different settings"));
    }

    @Test
    void aBadKeyIsTheAccountsProblemAndAMissingPermissionIsNot() {
        answers.put("POST /refunds", "401:{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Invalid API Key provided: sk_test_***1\"}}");
        assertThrows(AccountRejectedException.class, () -> refund(Map.of("paymentIntentId", "pi_9")));
        answers.put("POST /refunds", "403:{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"The provided key does not have the required permissions\"}}");
        PermanentStepException e = assertThrows(PermanentStepException.class, () -> refund(Map.of("paymentIntentId", "pi_9")));
        assertFalse(e instanceof AccountRejectedException);
        assertTrue(e.getMessage().contains("isn't allowed"), e.getMessage());
    }

    @Test
    void rateLimitsAndKeysInUseAreRetried() {
        answers.put("POST /refunds", "429:{\"error\":{\"type\":\"rate_limit_error\"}}");
        assertTrue(assertThrows(IllegalStateException.class, () -> refund(Map.of("paymentIntentId", "pi_9"))).getMessage().contains("rate limit"));
        answers.put("POST /refunds", "409:{\"error\":{\"type\":\"idempotency_error\",\"message\":\"in use\"}}");
        assertInstanceOf(IllegalStateException.class, assertThrows(Exception.class, () -> refund(Map.of("paymentIntentId", "pi_9"))));
    }

    // Stripe saves a 500 under the idempotency key and would answer every retry with it, so
    // retrying can't help; and it may have gone through, so the user is told to check.
    @Test
    void aServerErrorStopsTheStepAndSaysToCheck() {
        answers.put("POST /refunds", "500:{\"error\":{\"type\":\"api_error\"}}");
        PermanentStepException e = assertThrows(PermanentStepException.class, () -> refund(Map.of("paymentIntentId", "pi_9")));
        assertTrue(e.getMessage().contains("may or may not have gone through") && e.getMessage().contains("payment pi_9"), e.getMessage());
    }
}
