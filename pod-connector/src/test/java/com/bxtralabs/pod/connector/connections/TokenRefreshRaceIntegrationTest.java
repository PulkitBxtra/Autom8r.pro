package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs against a real Postgres (row locks can't be mocked) and a mock OAuth provider whose
// refresh tokens rotate: reusing an old refresh token gets invalid_grant, just like a real
// provider. Many requests needing the token at once, racing the scheduler, must cause exactly
// one refresh and all get the same new token. Only runs with CONNECTOR_IT=1 plus the DB_* and
// CONNECTIONS_ENCRYPTION_KEY env vars and the mock provider at CONNECTOR_IT_OAUTH_BASE.
@EnabledIfEnvironmentVariable(named = "CONNECTOR_IT", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "connectors.github.oauth-base=${CONNECTOR_IT_OAUTH_BASE:http://127.0.0.1:18988}",
        "connectors.github.api-base=${CONNECTOR_IT_OAUTH_BASE:http://127.0.0.1:18988}",
        "connectors.github.oauth.client-id=test-client",
        "connectors.github.oauth.client-secret=test-secret",
        "connections.refresh-interval-ms=3600000",
        "jwt.secret=integration-test-secret-integration-test-secret"
})
class TokenRefreshRaceIntegrationTest {

    static final int REQUESTS = 8;

    @Autowired OAuthService oauth;
    @Autowired TokenService tokens;
    @Autowired TokenRefreshScheduler scheduler;
    @Autowired ConnectionRepository connections;
    @Autowired JdbcTemplate jdbc;

    final HttpClient http = HttpClient.newHttpClient(); // doesn't follow redirects
    final String mockBase = System.getenv().getOrDefault("CONNECTOR_IT_OAUTH_BASE", "http://127.0.0.1:18988");

    @Test
    void concurrentRequestsAndSchedulerRefreshExactlyOnce() throws Exception {
        String connectionId = signIn("usr_it_" + System.nanoTime());
        try {
            // The first refresh of a brand-new connection may come from another running
            // pod-connector's scheduler; let that settle before counting.
            Thread.sleep(6000);
            int refreshesBefore = stat("refreshes");
            int invalidBefore = stat("invalid_grant");
            jdbc.update("update connection set expires_at = ? where id = ?",
                    System.currentTimeMillis() + 5_000, connectionId);

            ExecutorService pool = Executors.newFixedThreadPool(REQUESTS + 2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return tokens.getValidCredentials(connectionId).get(TokenService.ACCESS_TOKEN);
                }));
            }
            List<Future<?>> runs = List.of(
                    pool.submit(() -> { await(go); scheduler.refreshExpiring(); }),
                    pool.submit(() -> { await(go); scheduler.refreshExpiring(); }));
            go.countDown();

            List<String> accessTokens = new ArrayList<>();
            for (Future<String> f : results) {
                accessTokens.add(f.get());
            }
            for (Future<?> f : runs) {
                f.get();
            }
            pool.shutdown();

            assertEquals(1, stat("refreshes") - refreshesBefore, "exactly one refresh");
            assertEquals(0, stat("invalid_grant") - invalidBefore, "no request reused a rotated refresh token");
            assertEquals(1, accessTokens.stream().distinct().count(), "every request got the same new token: " + accessTokens);
            Connection c = connections.findById(connectionId).orElseThrow();
            assertEquals(Connection.STATUS_ACTIVE, c.getStatus());
            assertTrue(c.getExpiresAt() > System.currentTimeMillis() + 60_000, "expiry moved forward");
        } finally {
            connections.deleteById(connectionId);
        }
    }

    // start -> the provider's authorize page (auto-approves) -> callback, as the browser would.
    private String signIn(String userId) throws Exception {
        String authorizeUrl = oauth.start(userId, "app_github", null);
        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(authorizeUrl)).build(),
                HttpResponse.BodyHandlers.discarding());
        URI callback = URI.create(r.headers().firstValue("Location").orElseThrow());
        Map<String, String> q = new java.util.HashMap<>();
        for (String pair : callback.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            q.put(kv[0], java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        OAuthService.Result result = oauth.complete(q.get("code"), q.get("state"), null, null);
        assertTrue(result.success(), result.message());
        return result.connectionId();
    }

    private int stat(String name) throws Exception {
        String body = http.send(HttpRequest.newBuilder(URI.create(mockBase + "/test/stats")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        Matcher m = Pattern.compile("\"" + name + "\": (\\d+)").matcher(body);
        assertTrue(m.find(), body);
        return Integer.parseInt(m.group(1));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
