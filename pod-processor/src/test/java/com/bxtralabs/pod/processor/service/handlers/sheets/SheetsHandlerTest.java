package com.bxtralabs.pod.processor.service.handlers.sheets;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.AccountRejectedException;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import com.bxtralabs.pod.processor.service.handlers.StepContext;
import com.bxtralabs.pod.processor.service.handlers.StepCredentials;
import com.bxtralabs.pod.processor.service.handlers.UncertainStepException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SheetsHandlerTest {

    private static final String ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private SheetsHandler handler;
    // "METHOD decoded-path?query body" per call.
    private final List<String> calls = new ArrayList<>();
    // decoded path (without query) -> "status:body"
    private final Map<String, String> answers = new HashMap<>();

    private static final StepCredentials ACCOUNT = new StepCredentials("con_1", "app_sheets", "OAUTH", Map.of("access_token", "ya29.1"));
    private static final StepContext FIRST = new StepContext("str_abc", 1, 0, false);
    private static final StepContext RETRY = new StepContext("str_abc", 2, 0, true);

    @BeforeEach
    void start() throws IOException {
        answers.put("/spreadsheets/" + ID + "/values/'Orders'!1:1", "200:{\"values\":[[\"Order\",\"Customer Email\",\"Total\"]]}");
        answers.put("/spreadsheets/" + ID + "/values/'Orders'!A1:append", "200:{\"updates\":{\"updatedRange\":\"Orders!A7:C7\"}}");
        answers.put("/spreadsheets/" + ID + "/values:batchUpdate", "200:{\"totalUpdatedCells\":2}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = URLDecoder.decode(ex.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
            String query = ex.getRequestURI().getQuery();
            calls.add(ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query) + " " + body);
            String answer = answers.getOrDefault(path, "404:{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\"}}");
            byte[] out = answer.substring(4).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(Integer.parseInt(answer.substring(0, 3)), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        handler = new SheetsHandler(json, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static GraphNode node(String type) {
        return new GraphNode("a", "action", "Google Sheets", "item", "Step", type, Map.of(), null, "app_sheets", "con_1", null);
    }

    private Map<String, Object> add(Map<String, Object> values, StepContext context) throws Exception {
        return handler.execute(node(SheetsHandler.ADD_ROW), Map.of("spreadsheetId", "https://docs.google.com/spreadsheets/d/" + ID + "/edit#gid=0",
                "sheet", "Orders", "values", values), ACCOUNT, context);
    }

    private Map<?, ?> bodyOf(String call) {
        return json.readValue(call.substring(call.indexOf('{')), Map.class);
    }

    @Test
    void aRowIsAddedUnderTheMatchingHeadersInAnyOrder() throws Exception {
        Map<String, Object> out = add(new LinkedHashMap<>(Map.of("total", 42, "Order", "1042")), FIRST);

        assertEquals("GET /spreadsheets/" + ID + "/values/'Orders'!1:1 ", calls.getFirst());
        assertTrue(calls.getLast().startsWith("POST /spreadsheets/" + ID + "/values/'Orders'!A1:append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS "),
                calls.getLast());
        assertEquals(Map.of("values", List.of(List.of("1042", "", "42"))), bodyOf(calls.getLast()), "Total found ignoring case; the gap left empty");
        assertEquals(7L, out.get("rowNumber"));
        assertEquals(Map.of("Order", "1042", "Total", "42"), out.get("values"));
    }

    @Test
    void updatingSetsOnlyTheNamedCellsOfThatRow() throws Exception {
        Map<String, Object> out = handler.execute(node(SheetsHandler.UPDATE_ROW), Map.of("spreadsheetId", ID, "sheet", "Orders",
                "rowNumber", "5", "values", Map.of("Customer Email", "ada@example.com", "Total", 50)), ACCOUNT, FIRST);
        Map<?, ?> body = bodyOf(calls.getLast());
        assertEquals("USER_ENTERED", body.get("valueInputOption"));
        assertEquals(List.of(Map.of("range", "'Orders'!B5", "values", List.of(List.of("ada@example.com"))),
                Map.of("range", "'Orders'!C5", "values", List.of(List.of("50")))), body.get("data"));
        assertEquals(5L, out.get("rowNumber"));
        assertEquals(2, out.get("updatedCells"));
    }

    @Test
    void unknownColumnsListTheSheetsColumns() {
        PermanentStepException e = assertThrows(PermanentStepException.class, () -> add(Map.of("Price", 1), FIRST));
        assertEquals("Sheet \"Orders\" has no column \"Price\". Its columns: Order, Customer Email, Total", e.getMessage());
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("POST")));
    }

    @Test
    void settingsThatCantWorkFailBeforeWriting() {
        assertTrue(assertThrows(PermanentStepException.class, () -> handler.execute(node(SheetsHandler.ADD_ROW),
                Map.of("spreadsheetId", "my sheet", "sheet", "Orders", "values", Map.of("Order", 1)), ACCOUNT, FIRST))
                .getMessage().contains("isn't a Google Sheets link or ID"));
        assertTrue(assertThrows(PermanentStepException.class, () -> handler.execute(node(SheetsHandler.UPDATE_ROW),
                Map.of("spreadsheetId", ID, "sheet", "Orders", "rowNumber", 1, "values", Map.of("Order", 1)), ACCOUNT, FIRST))
                .getMessage().contains("2 or more"));
        assertTrue(assertThrows(PermanentStepException.class, () -> handler.execute(node(SheetsHandler.ADD_ROW),
                Map.of("spreadsheetId", ID, "sheet", "Orders", "values", Map.of()), ACCOUNT, FIRST))
                .getMessage().contains("at least one column"));
        answers.put("/spreadsheets/" + ID + "/values/'Empty'!1:1", "200:{}");
        assertTrue(assertThrows(PermanentStepException.class, () -> handler.execute(node(SheetsHandler.ADD_ROW),
                Map.of("spreadsheetId", ID, "sheet", "Empty", "values", Map.of("Order", 1)), ACCOUNT, FIRST))
                .getMessage().contains("has no column headers"));
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("POST")));
    }

    @Test
    void aRetryFindsTheRowAnEarlierAttemptAdded() throws Exception {
        answers.put("/spreadsheets/" + ID + "/values/'Orders'!A:C", "200:{\"values\":[[\"Order\",\"Customer Email\",\"Total\"],"
                + "[\"1041\",\"\",\"10\"],[\"1042\",\"\",\"42\"]]}");
        Map<String, Object> out = add(Map.of("Order", "1042", "Total", 42), RETRY);
        assertEquals(3L, out.get("rowNumber"));
        assertEquals(true, out.get("alreadyDone"));
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("POST")));

        add(Map.of("Order", "1043", "Total", 42), RETRY);
        assertTrue(calls.getLast().startsWith("POST "), "not there: added");
    }

    @Test
    void googlesAnswersAreSorted() {
        answers.put("/spreadsheets/" + ID + "/values/'Orders'!1:1", "401:{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\"}}");
        assertThrows(AccountRejectedException.class, () -> add(Map.of("Order", 1), FIRST));
        answers.put("/spreadsheets/" + ID + "/values/'Orders'!1:1", "403:{\"error\":{\"code\":403,\"message\":\"The caller does not have permission\"}}");
        assertTrue(assertThrows(PermanentStepException.class, () -> add(Map.of("Order", 1), FIRST)).getMessage().contains("Editor access"));
        answers.put("/spreadsheets/" + ID + "/values/'Orders'!1:1", "400:{\"error\":{\"code\":400,\"message\":\"Unable to parse range: 'Orders'!1:1\"}}");
        assertTrue(assertThrows(PermanentStepException.class, () -> add(Map.of("Order", 1), FIRST)).getMessage().contains("no tab named \"Orders\""));
        answers.put("/spreadsheets/" + ID + "/values/'Orders'!1:1", "200:{\"values\":[[\"Order\"]]}");
        answers.put("/spreadsheets/" + ID + "/values/'Orders'!A1:append", "503:{}");
        assertThrows(UncertainStepException.class, () -> add(Map.of("Order", 1), FIRST));
    }

    @Test
    void columnLettersAndQuotedSheetNames() {
        assertEquals("A", SheetsHandler.columnLetters(0));
        assertEquals("Z", SheetsHandler.columnLetters(25));
        assertEquals("AA", SheetsHandler.columnLetters(26));
        assertEquals("BA", SheetsHandler.columnLetters(52));
        assertEquals("'Q3 ''Final'''!A1", SheetsHandler.a1("Q3 'Final'", "A1"));
    }
}
