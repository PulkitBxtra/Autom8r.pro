package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.connections.CredentialCipher;
import com.bxtralabs.pod.connector.connections.TokenService;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.model.TriggerSubscription;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import com.bxtralabs.pod.connector.repository.TriggerDeliveryRepository;
import com.bxtralabs.pod.connector.repository.TriggerSubscriptionRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Gmail and Sheets triggers (polled), and the poller that runs them.
class GoogleTriggersTest {

    private static final Map<String, String> CREDS = Map.of("access_token", "ya29.1");
    private static final String SHEET_ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";

    private final JsonMapper json = JsonMapper.builder().build();
    private final ConnectionRepository connections = mock(ConnectionRepository.class);
    private HttpServer server;
    private String base;
    // decoded path (no query) -> body; anything else 404.
    private final Map<String, String> answers = new HashMap<>();
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = URLDecoder.decode(ex.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
            calls.add(path + (ex.getRequestURI().getQuery() == null ? "" : "?" + ex.getRequestURI().getQuery()));
            String answer = answers.get(path);
            byte[] out = (answer == null ? "{\"error\":{\"code\":404}}" : answer).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(answer == null ? 404 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TriggerSubscription subscription(String appId, String triggerId, Map<String, Object> config, String scopes) {
        Connection c = new Connection();
        c.setId("con_1");
        c.setScopes(scopes);
        when(connections.findById("con_1")).thenReturn(Optional.of(c));
        TriggerSubscription s = new TriggerSubscription();
        s.setId("tsub_1");
        s.setWorkflowId("wfl_1");
        s.setAppId(appId);
        s.setTriggerId(triggerId);
        s.setConnectionId("con_1");
        s.setConfig(new LinkedHashMap<>(config));
        return s;
    }

    // ---- Gmail ----

    private static final String READ = "openid https://www.googleapis.com/auth/gmail.compose https://www.googleapis.com/auth/gmail.readonly";

    private GmailTriggers gmail() {
        return new GmailTriggers(json, connections, base);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private void message(String id, boolean attachment) {
        answers.put("/users/me/messages/" + id, "{\"id\":\"" + id + "\",\"threadId\":\"t-" + id + "\",\"snippet\":\"Hi there\",\"labelIds\":[\"INBOX\"],"
                + "\"payload\":{\"mimeType\":\"multipart/mixed\",\"headers\":[{\"name\":\"From\",\"value\":\"Ada <ada@example.com>\"},"
                + "{\"name\":\"Subject\",\"value\":\"Invoice " + id + "\"},{\"name\":\"Date\",\"value\":\"Sun, 5 Oct 2026 10:00:00 +0000\"}],"
                + "\"parts\":[{\"mimeType\":\"multipart/alternative\",\"parts\":["
                + "{\"mimeType\":\"text/plain\",\"body\":{\"data\":\"" + b64("Please pay.") + "\"}},"
                + "{\"mimeType\":\"text/html\",\"body\":{\"data\":\"" + b64("<p>Please <b>pay</b>.</p>") + "\"}}]}"
                + (attachment ? ",{\"mimeType\":\"application/pdf\",\"filename\":\"invoice.pdf\",\"body\":{\"size\":2048,\"attachmentId\":\"a1\"}}" : "")
                + "]}}");
    }

    @Test
    void gmailNeedsReadAccessAndStartsFromNow() throws Exception {
        answers.put("/users/me/profile", "{\"historyId\":\"500\"}");
        TriggerSubscription without = subscription("app_gmail", GmailTriggers.NEW_EMAIL, Map.of(), "openid https://www.googleapis.com/auth/gmail.compose");
        assertTrue(assertThrows(TriggerSetupException.class, () -> gmail().register(without, CREDS, "", "")).getMessage().contains("Reconnect"));

        TriggerSubscription s = subscription("app_gmail", GmailTriggers.NEW_EMAIL, Map.of(), READ);
        gmail().register(s, CREDS, "", "");
        assertEquals("500", s.getMeta().get("historyId"));
    }

    @Test
    void newInboxMessagesSinceTheLastCheckBecomeEvents() throws Exception {
        TriggerSubscription s = subscription("app_gmail", GmailTriggers.NEW_EMAIL, Map.of(), READ);
        s.setMeta(Map.of("historyId", "500"));
        answers.put("/users/me/history", "{\"historyId\":\"510\",\"history\":["
                + "{\"messagesAdded\":[{\"message\":{\"id\":\"m1\",\"labelIds\":[\"INBOX\",\"UNREAD\"]}}]},"
                + "{\"messagesAdded\":[{\"message\":{\"id\":\"m2\",\"labelIds\":[\"SENT\"]}}]},"
                + "{\"messagesAdded\":[{\"message\":{\"id\":\"m3\",\"labelIds\":[\"INBOX\"]}}]}]}");
        message("m1", false);
        message("m3", true);

        PollingTriggers.Poll poll = gmail().poll(s, CREDS);

        assertTrue(calls.contains("/users/me/history?startHistoryId=500&historyTypes=messageAdded&maxResults=100"), calls.toString());
        assertEquals(List.of("gmail:m1", "gmail:m3"), poll.events().stream().map(PollingTriggers.Event::key).toList(), "sent mail isn't new mail");
        Map<String, Object> first = poll.events().getFirst().body();
        assertEquals("Ada <ada@example.com>", first.get("from"));
        assertEquals("Invoice m1", first.get("subject"));
        assertEquals("Please pay.", first.get("text"), "the plain text part");
        assertEquals(List.of(), first.get("attachments"));
        assertEquals(List.of(Map.of("filename", "invoice.pdf", "mimeType", "application/pdf", "size", 2048)),
                poll.events().get(1).body().get("attachments"));
        assertEquals(Map.of("historyId", "510"), poll.meta());
    }

    @Test
    void theAttachmentTriggerSkipsMailWithoutOne() throws Exception {
        TriggerSubscription s = subscription("app_gmail", GmailTriggers.NEW_ATTACHMENT, Map.of(), READ);
        s.setMeta(Map.of("historyId", "500"));
        answers.put("/users/me/history", "{\"historyId\":\"510\",\"history\":[{\"messagesAdded\":["
                + "{\"message\":{\"id\":\"m1\",\"labelIds\":[\"INBOX\"]}},{\"message\":{\"id\":\"m3\",\"labelIds\":[\"INBOX\"]}}]}]}");
        message("m1", false);
        message("m3", true);
        assertEquals(List.of("gmail:m3"), gmail().poll(s, CREDS).events().stream().map(PollingTriggers.Event::key).toList());
    }

    @Test
    void theLabelTriggerFindsTheLabelAndWatchesItBeingAdded() throws Exception {
        answers.put("/users/me/profile", "{\"historyId\":\"500\"}");
        answers.put("/users/me/labels", "{\"labels\":[{\"id\":\"INBOX\",\"name\":\"INBOX\"},{\"id\":\"Label_7\",\"name\":\"Invoices\"}]}");
        TriggerSubscription s = subscription("app_gmail", GmailTriggers.NEW_LABEL, Map.of("label", "invoices"), READ);
        gmail().register(s, CREDS, "", "");
        assertEquals("Label_7", s.getMeta().get("labelId"));
        TriggerSubscription missing = subscription("app_gmail", GmailTriggers.NEW_LABEL, Map.of("label", "Receipts"), READ);
        assertTrue(assertThrows(TriggerSetupException.class, () -> gmail().register(missing, CREDS, "", "")).getMessage().contains("no label \"Receipts\""));

        answers.put("/users/me/history", "{\"historyId\":\"520\",\"history\":["
                + "{\"labelsAdded\":[{\"message\":{\"id\":\"m1\",\"labelIds\":[\"INBOX\",\"Label_7\"]},\"labelIds\":[\"Label_7\"]}]},"
                + "{\"labelsAdded\":[{\"message\":{\"id\":\"m3\",\"labelIds\":[\"INBOX\",\"STARRED\"]},\"labelIds\":[\"STARRED\"]}]}]}");
        message("m1", false);
        PollingTriggers.Poll poll = gmail().poll(s, CREDS);
        assertEquals(List.of("gmail:m1:Label_7"), poll.events().stream().map(PollingTriggers.Event::key).toList());
        assertTrue(calls.stream().anyMatch(c -> c.contains("historyTypes=labelAdded")));
    }

    @Test
    void whenGmailHasForgottenThatFarBackPollingRestartsFromNow() throws Exception {
        TriggerSubscription s = subscription("app_gmail", GmailTriggers.NEW_EMAIL, Map.of(), READ);
        s.setMeta(Map.of("historyId", "1"));
        answers.put("/users/me/profile", "{\"historyId\":\"900\"}");
        PollingTriggers.Poll poll = gmail().poll(s, CREDS);
        assertTrue(poll.events().isEmpty());
        assertEquals(Map.of("historyId", "900"), poll.meta());
    }

    // ---- Sheets ----

    private SheetsTriggers sheets() {
        return new SheetsTriggers(json, base);
    }

    private void sheet(String rowsJson) {
        answers.put("/spreadsheets/" + SHEET_ID + "/values/'Orders'", "{\"values\":" + rowsJson + "}");
    }

    @Test
    void newRowsBelowTheLastOneSeenBecomeEventsByHeader() throws Exception {
        sheet("[[\"Order\",\"Email\"],[\"1041\",\"a@example.com\"]]");
        TriggerSubscription s = subscription("app_sheets", SheetsTriggers.NEW_ROW,
                Map.of("spreadsheetId", "https://docs.google.com/spreadsheets/d/" + SHEET_ID + "/edit", "sheet", "Orders"), null);
        sheets().register(s, CREDS, "", "");
        assertEquals(Map.of("rowCount", 2), s.getMeta());

        sheet("[[\"Order\",\"Email\"],[\"1041\",\"a@example.com\"],[\"1042\",\"b@example.com\"],[],[\"1043\"]]");
        PollingTriggers.Poll poll = sheets().poll(s, CREDS);
        assertEquals(2, poll.events().size(), "the blank row is skipped");
        assertEquals(3, poll.events().getFirst().body().get("rowNumber"));
        assertEquals(Map.of("Order", "1042", "Email", "b@example.com"), poll.events().getFirst().body().get("values"));
        assertEquals(Map.of("Order", "1043", "Email", ""), poll.events().get(1).body().get("values"));
        assertEquals(Map.of("rowCount", 5), poll.meta());
    }

    @Test
    void changedRowsBecomeEventsAndAddedOnesDont() throws Exception {
        sheet("[[\"Order\",\"Status\"],[\"1041\",\"new\"],[\"1042\",\"new\"]]");
        TriggerSubscription s = subscription("app_sheets", SheetsTriggers.UPDATED_ROW, Map.of("spreadsheetId", SHEET_ID, "sheet", "Orders"), null);
        sheets().register(s, CREDS, "", "");

        sheet("[[\"Order\",\"Status\"],[\"1041\",\"new\"],[\"1042\",\"paid\"],[\"1043\",\"new\"]]");
        PollingTriggers.Poll poll = sheets().poll(s, CREDS);
        assertEquals(1, poll.events().size());
        assertEquals(3, poll.events().getFirst().body().get("rowNumber"));
        assertEquals(Map.of("Order", "1042", "Status", "paid"), poll.events().getFirst().body().get("values"));
        assertEquals(4, ((List<?>) poll.meta().get("hashes")).size());
    }

    @Test
    void aSheetWithoutHeadersOrTabSaysSo() {
        sheet("[]");
        TriggerSubscription s = subscription("app_sheets", SheetsTriggers.NEW_ROW, Map.of("spreadsheetId", SHEET_ID, "sheet", "Orders"), null);
        assertTrue(assertThrows(TriggerSetupException.class, () -> sheets().register(s, CREDS, "", "")).getMessage().contains("no column headers"));
        TriggerSubscription bad = subscription("app_sheets", SheetsTriggers.NEW_ROW, Map.of("spreadsheetId", "my sheet", "sheet", "Orders"), null);
        assertTrue(assertThrows(TriggerSetupException.class, () -> sheets().register(bad, CREDS, "", "")).getMessage().contains("isn't a Google Sheets link"));
    }

    // ---- the poller ----

    @Test
    void thePollerStartsEachNewEventOnceAndKeepsTheCheckpointIfAStartFails() throws Exception {
        TriggerSubscriptionRepository subs = mock(TriggerSubscriptionRepository.class);
        TriggerDeliveryRepository deliveries = mock(TriggerDeliveryRepository.class);
        TokenService tokens = mock(TokenService.class);
        RunStarter runs = mock(RunStarter.class);
        PollingTriggers poller = mock(PollingTriggers.class);
        Set<String> seen = new HashSet<>();
        when(deliveries.recordNew(any(), any(), anyLong())).thenAnswer(inv -> seen.add(inv.getArgument(0)) ? 1 : 0);
        doAnswer(inv -> seen.remove((String) inv.getArgument(0))).when(deliveries).deleteById(any());
        when(tokens.getValidCredentials("con_1")).thenReturn(CREDS);
        when(poller.supports("app_sheets")).thenReturn(true);
        TriggerPoller loop = new TriggerPoller(subs, deliveries, tokens, mock(CredentialCipher.class), List.of(poller), runs, null, 60_000);

        TriggerSubscription s = subscription("app_sheets", SheetsTriggers.NEW_ROW, Map.of(), null);
        s.setMeta(Map.of("rowCount", 2));
        PollingTriggers.Poll found = new PollingTriggers.Poll(List.of(new PollingTriggers.Event("sheets:3:x", Map.of("rowNumber", 3)),
                new PollingTriggers.Event("sheets:4:y", Map.of("rowNumber", 4))), Map.of("rowCount", 4));
        when(poller.poll(any(), any())).thenReturn(found);
        when(runs.start(any(), any())).thenReturn("run_1").thenThrow(new IllegalStateException("pod-webhooks is down"));

        loop.pollOne(s);
        assertEquals(2, s.getMeta().get("rowCount"), "a start failed: the checkpoint stays");
        assertEquals("pod-webhooks is down", s.getLastError());

        reset(runs);
        loop.pollOne(s);
        verify(runs, times(1)).start("wfl_1", Map.of("rowNumber", 4)); // row 3 already started
        assertEquals(4, s.getMeta().get("rowCount"));
        assertNull(s.getLastError());
        assertNotNull(s.getLastEventAt());
    }
}
