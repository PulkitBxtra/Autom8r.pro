package com.bxtralabs.pod.processor.service.handlers.notion;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NotionHandlerTest {

    private static final String DB = "11111111-1111-1111-1111-111111111111";
    private static final String PAGE = "22222222-2222-2222-2222-222222222222";
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private NotionHandler handler;
    private final List<String> requests = new ArrayList<>();
    private final List<Map<?, ?>> bodies = new ArrayList<>();
    private volatile int pageStatus = 200;

    private static final StepCredentials SECRET = new StepCredentials("con_1", "app_notion", "TOKEN", Map.of("token", "ntn_1"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(ex.getRequestMethod() + " " + path + " " + ex.getRequestHeaders().getFirst("Notion-Version"));
            if (!body.isEmpty()) bodies.add(json.readValue(body, Map.class));
            if (path.startsWith("/databases/")) {
                if (path.endsWith(DB)) respond(ex, 200, "{\"id\":\"" + DB + "\",\"properties\":{\"Task\":{\"type\":\"title\"},\"Due\":{\"type\":\"date\"}}}");
                else respond(ex, 404, "{\"object\":\"error\",\"code\":\"object_not_found\",\"message\":\"Could not find database\"}");
                return;
            }
            switch (pageStatus) {
                case 200 -> respond(ex, 200, "{\"id\":\"p1\",\"url\":\"https://www.notion.so/p1\",\"last_edited_time\":\"2026-09-28T12:00:00.000Z\"}");
                case 404 -> respond(ex, 404, "{\"object\":\"error\",\"code\":\"object_not_found\",\"message\":\"Could not find page\"}");
                case 400 -> respond(ex, 400, "{\"object\":\"error\",\"code\":\"validation_error\",\"message\":\"Due is expected to be date.\"}");
                default -> respond(ex, pageStatus, "{\"object\":\"error\",\"code\":\"rate_limited\",\"message\":\"slow down\"}");
            }
        });
        server.start();
        handler = new NotionHandler(json, "http://127.0.0.1:" + server.getAddress().getPort() + "/");
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
        return new GraphNode("a", "action", "Notion", "item", "Step", type, Map.of(), null, "app_notion", "con_1", null);
    }

    @Test
    void createsARowInADatabaseUnderItsTitleColumnFromAPastedLink() throws Exception {
        Map<String, Object> out = handler.execute(node(NotionHandler.CREATE_PAGE), Map.of(
                "parentId", "https://www.notion.so/acme/Tasks-" + DB.replace("-", "") + "?v=abc",
                "title", "Ship it", "properties", Map.of("Due", Map.of("date", Map.of("start", "2026-10-01"))),
                "content", "First line\n\nSecond line"), SECRET);

        assertEquals("GET /databases/" + DB + " 2022-06-28", requests.get(0));
        assertEquals("POST /pages 2022-06-28", requests.get(1));
        Map<?, ?> body = bodies.getFirst();
        assertEquals(Map.of("database_id", DB), body.get("parent"));
        Map<?, ?> props = (Map<?, ?>) body.get("properties");
        assertTrue(props.containsKey("Task") && props.containsKey("Due"), props.toString());
        assertEquals(2, ((List<?>) body.get("children")).size(), "one paragraph per non-empty line");
        assertEquals(Map.of("id", "p1", "url", "https://www.notion.so/p1", "parent", "database"), out);
    }

    @Test
    void createsASubpageWhenTheParentIsAPage() throws Exception {
        Map<String, Object> out = handler.execute(node(NotionHandler.CREATE_PAGE), Map.of("parentId", PAGE, "title", "Notes"), SECRET);
        Map<?, ?> body = bodies.getFirst();
        assertEquals(Map.of("page_id", PAGE), body.get("parent"));
        assertTrue(((Map<?, ?>) body.get("properties")).containsKey("title"));
        assertEquals("page", out.get("parent"));
        // A page can't take database properties.
        assertThrows(PermanentStepException.class, () -> handler.execute(node(NotionHandler.CREATE_PAGE),
                Map.of("parentId", PAGE, "title", "x", "properties", Map.of("Status", Map.of())), SECRET));
    }

    @Test
    void updatesAPagesPropertiesGivenAsJsonText() throws Exception {
        Map<String, Object> out = handler.execute(node(NotionHandler.UPDATE_PAGE),
                Map.of("pageId", PAGE.replace("-", ""), "properties", "{\"Status\": {\"select\": {\"name\": \"Done\"}}}"), SECRET);
        assertEquals("PATCH /pages/" + PAGE + " 2022-06-28", requests.getFirst());
        assertEquals(Map.of("properties", Map.of("Status", Map.of("select", Map.of("name", "Done")))), bodies.getFirst());
        assertEquals("2026-09-28T12:00:00.000Z", out.get("lastEditedTime"));
    }

    @Test
    void userFixableProblemsFailForGood() {
        pageStatus = 404;
        assertTrue(assertThrows(PermanentStepException.class, () -> handler.execute(node(NotionHandler.UPDATE_PAGE),
                Map.of("pageId", PAGE, "properties", Map.of("a", 1)), SECRET)).getMessage().contains("Share it with the integration"));
        pageStatus = 400;
        assertEquals("Notion rejected the request: Due is expected to be date.", assertThrows(PermanentStepException.class,
                () -> handler.execute(node(NotionHandler.UPDATE_PAGE), Map.of("pageId", PAGE, "properties", Map.of("a", 1)), SECRET)).getMessage());
        assertThrows(PermanentStepException.class, () -> handler.execute(node(NotionHandler.UPDATE_PAGE),
                Map.of("pageId", "not-an-id", "properties", Map.of("a", 1)), SECRET));
        assertThrows(PermanentStepException.class, () -> handler.execute(node(NotionHandler.UPDATE_PAGE),
                Map.of("pageId", PAGE, "properties", "not json"), SECRET));
        assertThrows(PermanentStepException.class, () -> handler.execute(node(NotionHandler.UPDATE_PAGE),
                Map.of("pageId", PAGE, "properties", Map.of("a", 1)), null));
    }

    @Test
    void rateLimitsConflictsAndOutagesAreRetried() {
        for (int status : new int[]{429, 409, 502}) {
            pageStatus = status;
            assertThrows(IllegalStateException.class, () -> handler.execute(node(NotionHandler.UPDATE_PAGE),
                    Map.of("pageId", PAGE, "properties", Map.of("a", 1)), SECRET), "HTTP " + status);
        }
    }
}
