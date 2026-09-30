package com.bxtralabs.pod.sandbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import groovy.json.JsonOutput;
import groovy.json.JsonSlurperClassic;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// The Code-step service pod-processor calls: POST /run with {"script", "bindings",
// "timeoutSeconds"} runs the script in a fresh JVM (ProcessRunner) and answers with
// ScriptRunner's JSON. At most SANDBOX_CONCURRENCY scripts run at once; a request that can't get
// a turn within a few seconds gets 503, which pod-processor retries later.
//
//   200  the script's outcome, including its errors (ok: false)
//   401  missing or wrong X-Sandbox-Token (when SANDBOX_TOKEN is set)
//   413  request over 1 MB
//   503  busy, or the runner couldn't start: try again later
//
// Env: SANDBOX_PORT (8090), SANDBOX_TOKEN, SANDBOX_CONCURRENCY (4), SANDBOX_ISOLATION
// (auto | uid | sandbox-exec | none), SANDBOX_JAR (default: the jar this runs from).
public final class SandboxServer {

    static final int MAX_REQUEST_BYTES = 1024 * 1024;
    static final int MAX_TIMEOUT_SECONDS = 30;
    private static final long WAIT_FOR_TURN_SECONDS = 5;

    private final ProcessRunner runner;
    private final byte[] token;
    private final BlockingQueue<Integer> slots;

    public SandboxServer(ProcessRunner runner, String token, int concurrency) {
        this.runner = runner;
        this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
        this.slots = new ArrayBlockingQueue<>(concurrency);
        for (int i = 0; i < concurrency; i++) slots.add(i);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        int port = Integer.parseInt(env.getOrDefault("SANDBOX_PORT", "8090"));
        int concurrency = Integer.parseInt(env.getOrDefault("SANDBOX_CONCURRENCY", "4"));
        ProcessRunner.Isolation isolation = ProcessRunner.choose(env.get("SANDBOX_ISOLATION"));
        Path jar = env.containsKey("SANDBOX_JAR") ? Path.of(env.get("SANDBOX_JAR")) : ownJar();

        HttpServer server = start(new SandboxServer(ProcessRunner.forJar(jar, isolation), env.get("SANDBOX_TOKEN"), concurrency),
                port, concurrency);
        System.out.println("pod-sandbox listening on " + server.getAddress().getPort() + ", isolation " + isolation
                + ", " + concurrency + " at a time" + (env.containsKey("SANDBOX_TOKEN") ? "" : ", no token required"));
        if (isolation == ProcessRunner.Isolation.NONE) {
            System.out.println("WARNING: scripts run without OS isolation; only use this for development");
        }
    }

    public static HttpServer start(SandboxServer handler, int port, int concurrency) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 64);
        server.createContext("/run", handler::handleRun);
        server.createContext("/health", ex -> respond(ex, 200, "{\"ok\":true}"));
        server.setExecutor(Executors.newFixedThreadPool(concurrency + 4));
        server.start();
        return server;
    }

    void handleRun(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, error("POST a script to /run"));
                return;
            }
            if (token != null) {
                String given = ex.getRequestHeaders().getFirst("X-Sandbox-Token");
                if (given == null || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
                    respond(ex, 401, error("Missing or wrong X-Sandbox-Token"));
                    return;
                }
            }
            byte[] body = readCapped(ex.getRequestBody());
            if (body == null) {
                respond(ex, 413, error("The request is over 1 MB"));
                return;
            }
            String json = new String(body, StandardCharsets.UTF_8);
            int timeout;
            try {
                Map<?, ?> request = (Map<?, ?>) new JsonSlurperClassic().parseText(json);
                if (!(request.get("script") instanceof String)) {
                    respond(ex, 400, error("No script"));
                    return;
                }
                timeout = request.get("timeoutSeconds") instanceof Number n ? n.intValue() : 10;
            } catch (RuntimeException bad) {
                respond(ex, 400, error("The request isn't JSON"));
                return;
            }
            timeout = Math.max(1, Math.min(timeout, MAX_TIMEOUT_SECONDS));

            Integer slot = slots.poll(WAIT_FOR_TURN_SECONDS, TimeUnit.SECONDS);
            if (slot == null) {
                respond(ex, 503, error("Too many scripts are running; try again shortly"));
                return;
            }
            try {
                respond(ex, 200, runner.run(json, timeout, slot));
            } catch (ProcessRunner.UnavailableException e) {
                respond(ex, 503, error(e.getMessage()));
            } finally {
                slots.add(slot);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_REQUEST_BYTES + 1);
        return body.length > MAX_REQUEST_BYTES ? null : body;
    }

    private static String error(String message) {
        return JsonOutput.toJson(Map.of("error", message));
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
    }

    private static Path ownJar() throws URISyntaxException {
        return Path.of(SandboxServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
