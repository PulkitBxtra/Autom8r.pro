package com.bxtralabs.pod.sandbox;

import com.sun.net.httpserver.HttpServer;
import groovy.json.JsonOutput;
import groovy.json.JsonSlurperClassic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SandboxServerTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String start(ProcessRunner runner, String token, int concurrency) throws Exception {
        server = SandboxServer.start(new SandboxServer(runner, token, concurrency), 0, concurrency);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // The real runner, from the test classpath.
    private static ProcessRunner real() {
        return ProcessRunner.forJar(Path.of(System.getProperty("java.class.path")), ProcessRunner.Isolation.NONE);
    }

    // A stand-in child: a shell command reading the request on stdin.
    private static ProcessRunner shell(String script) {
        return new ProcessRunner((dir, slot) -> List.of("/bin/sh", "-c", script), ProcessRunner.Isolation.NONE, 500);
    }

    private HttpResponse<String> post(String base, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/run")).POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("X-Sandbox-Token", token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String request(String script, Map<String, ?> bindings, int timeout) {
        return JsonOutput.toJson(Map.of("script", script, "bindings", bindings, "timeoutSeconds", timeout));
    }

    @Test
    void runsAScriptInAFreshJvmAndAnswersWithItsOutcome() throws Exception {
        String base = start(real(), "t0ken-for-tests", 2);
        HttpResponse<String> r = post(base, request("println 'hi'\n[sum: a + b]", Map.of("a", 1, "b", 2), 5), "t0ken-for-tests");
        assertEquals(200, r.statusCode(), r.body());
        Map<?, ?> answer = (Map<?, ?>) new JsonSlurperClassic().parseText(r.body());
        assertEquals(Map.of("sum", 3), answer.get("result"));
        assertEquals("hi\n", answer.get("logs"));

        Map<?, ?> blocked = (Map<?, ?>) new JsonSlurperClassic().parseText(post(base, request("System.getenv()", Map.of(), 5), "t0ken-for-tests").body());
        assertEquals("blocked", blocked.get("kind"), "script errors are answers too");
    }

    @Test
    void theTokenIsRequiredWhenSet() throws Exception {
        String base = start(shell("cat > /dev/null; echo '{\"ok\":true}'"), "t0ken-for-tests", 1);
        assertEquals(401, post(base, request("1", Map.of(), 5), null).statusCode());
        assertEquals(401, post(base, request("1", Map.of(), 5), "wrong").statusCode());
        assertEquals(200, post(base, request("1", Map.of(), 5), "t0ken-for-tests").statusCode());
    }

    @Test
    void badRequestsAreRefused() throws Exception {
        String base = start(shell("cat > /dev/null; echo '{\"ok\":true}'"), null, 1);
        assertEquals(400, post(base, "not json", null).statusCode());
        assertEquals(400, post(base, "{\"bindings\":{}}", null).statusCode());
        assertEquals(413, post(base, request("x".repeat(SandboxServer.MAX_REQUEST_BYTES), Map.of(), 5), null).statusCode());
        assertEquals(405, http.send(HttpRequest.newBuilder(URI.create(base + "/run")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void whenEveryTurnIsTakenANewRequestIsToldToComeBack() throws Exception {
        String base = start(shell("cat > /dev/null; sleep 8; echo '{\"ok\":true}'"), null, 1);
        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
            try {
                return post(base, request("1", Map.of(), 10), null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(300);
        HttpResponse<String> second = post(base, request("1", Map.of(), 10), null);
        assertEquals(503, second.statusCode(), second.body());
        assertEquals(200, first.get(20, TimeUnit.SECONDS).statusCode());
    }

    @Test
    void aRunnerThatCantStartIs503() throws Exception {
        String base = start(shell("echo 'Error: could not start' >&2; exit 1"), null, 1);
        HttpResponse<String> r = post(base, request("1", Map.of(), 5), null);
        assertEquals(503, r.statusCode());
        assertTrue(r.body().contains("could not start"), r.body());
    }

    @Test
    void childrenGetNoEnvironmentAndTheirOwnDirectoryDeletedAfterwards() throws Exception {
        ProcessRunner env = shell("cat > /dev/null; printf '{\"ok\":true,\"result\":{\"env\":\"%s\",\"dir\":\"%s\"}}' "
                + "\"$(env | grep -v -e '^PWD=' -e '^SHLVL=' -e '^_=' | tr '\\n' ' ')\" \"$(pwd)\"");
        Map<?, ?> answer = (Map<?, ?>) new JsonSlurperClassic().parseText(env.run(request("1", Map.of(), 5), 5, 0));
        Map<?, ?> result = (Map<?, ?>) answer.get("result");
        assertEquals("", String.valueOf(result.get("env")).trim());
        assertTrue(String.valueOf(result.get("dir")).contains("autom8r-code-"));
        assertFalse(Files.exists(Path.of(String.valueOf(result.get("dir")))));
    }

    @Test
    void anOverrunningChildIsKilledAndOutOfMemoryIsTheScriptsFault() throws Exception {
        long started = System.nanoTime();
        Map<?, ?> slow = (Map<?, ?>) new JsonSlurperClassic().parseText(shell("sleep 30").run("{}", 1, 0));
        assertEquals("timeout", slow.get("kind"));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5);
        Map<?, ?> oom = (Map<?, ?>) new JsonSlurperClassic().parseText(
                shell("echo 'java.lang.OutOfMemoryError: Java heap space' >&2; exit 3").run("{}", 5, 0));
        assertEquals("memory", oom.get("kind"));
    }

    @Test
    void theChildCommandMatchesTheIsolation() throws Exception {
        Path dir = Files.createTempDirectory("t");
        Files.createDirectory(dir.resolve("work"));
        Path jar = Path.of("/opt/pod-sandbox.jar");
        List<String> uid = ProcessRunner.javaCommand(jar, dir, 2, ProcessRunner.Isolation.UID);
        assertEquals(List.of("setpriv", "--reuid=20002", "--regid=20002", "--clear-groups", "--no-new-privs", "--"), uid.subList(0, 6));
        assertTrue(uid.containsAll(List.of("-Xmx128m", "-cp", "/opt/pod-sandbox.jar", ScriptRunner.class.getName())));
        assertTrue(ProcessRunner.javaCommand(jar, dir, 0, ProcessRunner.Isolation.NONE).getFirst().endsWith("/bin/java"));
    }
}
