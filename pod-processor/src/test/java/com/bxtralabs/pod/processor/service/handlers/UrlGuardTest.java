package com.bxtralabs.pod.processor.service.handlers;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UrlGuardTest {

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    // Names resolve to what the test says; IP literals to themselves.
    private static UrlGuard guard(Map<String, String> dns) {
        return new UrlGuard(host -> {
            if (dns.containsKey(host)) {
                return dns.get(host).isEmpty() ? new InetAddress[0]
                        : java.util.Arrays.stream(dns.get(host).split(",")).map(UrlGuardTest::ip).toArray(InetAddress[]::new);
            }
            if (host.equals("nowhere.test")) throw new UnknownHostException(host);
            return new InetAddress[]{ip(host)};
        }, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8084/internal/connections/x/credentials",
            "http://localhost/",
            "http://0.0.0.0/",
            "http://10.1.2.3/",
            "http://172.16.0.1/", "http://172.31.255.255/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/",
            "http://100.64.0.1/",
            "http://198.18.0.1/",
            "http://224.0.0.1/", "http://255.255.255.255/",
            "http://[::1]/", "http://[::]/",
            "http://[fe80::1]/", "http://[fd00::1]/",
            "http://[::ffff:127.0.0.1]/", "http://[::ffff:a9fe:a9fe]/",
            "http://[64:ff9b::a9fe:a9fe]/",
            "http://[2002:a00:1::]/",
            "http://metadata.test/", "http://mixed.test/",
    })
    void internalAddressesAreRefused(String url) {
        UrlGuard g = guard(Map.of("localhost", "127.0.0.1", "metadata.test", "169.254.169.254",
                "mixed.test", "93.184.216.34,10.0.0.5"));
        PermanentStepException e = assertThrows(PermanentStepException.class, () -> g.check(URI.create(url)), url);
        assertTrue(e.getMessage().contains("private or internal address"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.example.com/v1", "http://93.184.216.34/", "http://172.32.0.1/", "http://[2606:4700::1111]/",
            "http://nowhere.test/"})
    void publicAddressesAreAllowed(String url) {
        UrlGuard g = guard(Map.of("api.example.com", "93.184.216.34"));
        assertDoesNotThrow(() -> g.check(URI.create(url)), "a name that doesn't resolve is left to the request");
    }

    @Test
    void onlyHttpAndHttps() {
        UrlGuard g = guard(Map.of());
        for (String url : List.of("file:///etc/passwd", "ftp://example.com/x", "gopher://example.com/", "jar:file:/x!/y")) {
            assertThrows(PermanentStepException.class, () -> g.check(URI.create(url)), url);
        }
        // The switch for local development only lifts the address check.
        assertDoesNotThrow(() -> new UrlGuard(UrlGuard.DNS, true).check(URI.create("http://127.0.0.1:8084/")));
        assertThrows(PermanentStepException.class, () -> new UrlGuard(UrlGuard.DNS, true).check(URI.create("file:///etc/passwd")));
    }

    // ---- through the HTTP step: redirects are checked hop by hop ----

    private HttpServer server;
    private String base;
    private final List<String> seen = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/drip")) {
                // Headers at once, then the body a byte every half second, for a minute.
                ex.sendResponseHeaders(200, 0);
                try {
                    for (int i = 0; i < 120; i++) {
                        ex.getResponseBody().write('x');
                        ex.getResponseBody().flush();
                        Thread.sleep(500);
                    }
                } catch (Exception gone) {
                    // the client gave up
                }
                ex.close();
                return;
            }
            if (path.startsWith("/slow-hop")) {
                try {
                    Thread.sleep(900);
                } catch (InterruptedException ignored) {
                }
                ex.getResponseHeaders().add("Location", "/slow-hop" + (path.length() + 1));
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getHost() + path
                    + " auth=" + ex.getRequestHeaders().getFirst("Authorization")
                    + " body=" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String location = switch (path) {
                case "/to-metadata" -> "http://169.254.169.254/latest/meta-data/";
                case "/to-self" -> "/final";
                case "/see-other" -> "/final";
                case "/elsewhere" -> "http://localhost:" + server.getAddress().getPort() + "/final";
                case "/loop" -> "/loop";
                default -> null;
            };
            int status = location == null ? 200 : path.equals("/see-other") ? 303 : 302;
            if (location != null) ex.getResponseHeaders().add("Location", location);
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
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

    // The test server is on 127.0.0.1; this guard treats that (and "localhost") as public so the
    // redirects can be exercised.
    private HttpRequestHandler handler() {
        return new HttpRequestHandler(JsonMapper.builder().build(), new UrlGuard(host -> host.equals("127.0.0.1") || host.equals("localhost")
                ? new InetAddress[]{ip("93.184.216.34")} : new InetAddress[]{ip(host)}, false));
    }

    private static final GraphNode NODE = new GraphNode("h", "action", "HTTP", "act_http_request", "Make a Request",
            "http_request", Map.of(), null, "app_http", null, null);

    @Test
    void aRedirectToAnInternalAddressIsRefusedBeforeItIsCalled() {
        PermanentStepException e = assertThrows(PermanentStepException.class,
                () -> handler().execute(NODE, Map.of("url", base + "/to-metadata")));
        assertTrue(e.getMessage().contains("169.254.169.254"), e.getMessage());
        assertEquals(1, seen.size(), "only the first hop was called");
    }

    @Test
    void redirectsAreFollowedKeepingCredentialsOnlyOnTheSameOrigin() throws Exception {
        Map<String, Object> same = handler().execute(NODE, Map.of("url", base + "/to-self", "headers", Map.of("Authorization", "Bearer s3cret")));
        assertEquals(200, same.get("status"));
        assertTrue(seen.get(1).startsWith("GET") && seen.get(1).contains("/final auth=Bearer s3cret"), seen.toString());

        seen.clear();
        handler().execute(NODE, Map.of("url", base + "/elsewhere", "headers", Map.of("Authorization", "Bearer s3cret", "X-Trace", "t1")));
        assertTrue(seen.get(1).contains("/final auth=null"), "another origin doesn't get the credential: " + seen);
    }

    @Test
    void aSeeOtherAfterAPostContinuesAsAGetWithoutTheBody() throws Exception {
        handler().execute(NODE, Map.of("url", base + "/see-other", "method", "POST", "body", Map.of("a", 1)));
        assertEquals("POST", seen.get(0).split(" ")[0]);
        assertTrue(seen.get(1).startsWith("GET") && seen.get(1).endsWith("body="), seen.toString());
    }

    @Test
    void endlessRedirectsStop() {
        PermanentStepException e = assertThrows(PermanentStepException.class, () -> handler().execute(NODE, Map.of("url", base + "/loop")));
        assertTrue(e.getMessage().startsWith("More than 5 redirects"), e.getMessage());
        assertEquals(HttpRequestHandler.MAX_REDIRECTS + 1, seen.size());
    }

    @Test
    void byDefaultTheStepCantReachThisMachine() {
        HttpRequestHandler real = new HttpRequestHandler(JsonMapper.builder().build(), false);
        assertThrows(PermanentStepException.class, () -> real.execute(NODE, Map.of("url", base + "/final")));
        assertTrue(seen.isEmpty());
    }

    @Test
    void theTimeoutCoversReadingASlowBody() {
        long started = System.nanoTime();
        Exception e = assertThrows(java.net.http.HttpTimeoutException.class,
                () -> handler().execute(NODE, Map.of("url", base + "/drip", "timeoutSeconds", 2)));
        long took = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(took < 4000, "stopped after " + took + "ms");
        assertTrue(e.getMessage().contains("didn't finish within 2 seconds"), e.getMessage());
    }

    @Test
    void theTimeoutCoversEveryRedirectHopTogether() {
        long started = System.nanoTime();
        assertThrows(java.net.http.HttpTimeoutException.class,
                () -> handler().execute(NODE, Map.of("url", base + "/slow-hop", "timeoutSeconds", 2)));
        long took = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(took < 4000, "5 hops of 0.9s each would be 4.5s; stopped after " + took + "ms");
    }
}
