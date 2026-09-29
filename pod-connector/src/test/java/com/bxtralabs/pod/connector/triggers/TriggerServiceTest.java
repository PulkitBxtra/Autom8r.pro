package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.common.NotFoundException;
import com.bxtralabs.pod.connector.connections.CredentialCipher;
import com.bxtralabs.pod.connector.connections.TokenService;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.model.TriggerSubscription;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import com.bxtralabs.pod.connector.repository.TriggerDeliveryRepository;
import com.bxtralabs.pod.connector.repository.TriggerSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TriggerServiceTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final TriggerSubscriptionRepository subs = mock(TriggerSubscriptionRepository.class);
    private final TriggerDeliveryRepository deliveries = mock(TriggerDeliveryRepository.class);
    private final ConnectionRepository connections = mock(ConnectionRepository.class);
    private final TokenService tokens = mock(TokenService.class);
    private final GitHubTriggers github = mock(GitHubTriggers.class);
    private final SlackTriggers slack = mock(SlackTriggers.class);
    private final RunStarter runs = mock(RunStarter.class);
    private static final String SLACK_SERVER_SECRET = "server-signing-secret";
    private final Map<String, TriggerSubscription> db = new LinkedHashMap<>();
    private final Set<String> seenDeliveries = new HashSet<>();
    private CredentialCipher cipher;
    private TriggerService service;

    @BeforeEach
    void setUp() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        cipher = new CredentialCipher(Base64.getEncoder().encodeToString(key), json);
        service = service("https://hooks.example.com/");

        when(github.supports("app_github")).thenReturn(true);
        when(subs.save(any())).thenAnswer(inv -> {
            TriggerSubscription s = inv.getArgument(0);
            if (s.getId() == null) s.setId("tsub_" + (db.size() + 1));
            db.put(s.getId(), s);
            return s;
        });
        when(subs.findById(any())).thenAnswer(inv -> Optional.ofNullable(db.get((String) inv.getArgument(0))));
        when(subs.findByWorkflowId(any())).thenAnswer(inv -> db.values().stream()
                .filter(s -> s.getWorkflowId().equals(inv.getArgument(0))).findFirst());
        doAnswer(inv -> db.remove(((TriggerSubscription) inv.getArgument(0)).getId())).when(subs).delete(any());
        when(deliveries.recordNew(any(), any(), anyLong())).thenAnswer(inv -> seenDeliveries.add(inv.getArgument(0)) ? 1 : 0);
        doAnswer(inv -> seenDeliveries.remove((String) inv.getArgument(0))).when(deliveries).deleteById(any());

        Connection c = new Connection();
        c.setId("con_1");
        c.setUserId("usr_1");
        c.setAppId("app_github");
        when(connections.findById("con_1")).thenReturn(Optional.of(c));
        when(tokens.getValidCredentials("con_1")).thenReturn(Map.of("access_token", "gho_1"));
        when(github.register(any(), any(), any(), any())).thenReturn("hook_77");
    }

    private TriggerService service(String publicUrl) {
        return new TriggerService(subs, deliveries, connections, tokens, cipher, List.of(github, slack), github, slack, runs, json,
                publicUrl, SLACK_SERVER_SECRET);
    }

    private TriggerService.Status subscribe(String repo) {
        return service.subscribe("wfl_1", "usr_1", "app_github", GitHubTriggers.NEW_ISSUE, "con_1", Map.of("repository", repo));
    }

    @Test
    void subscribingRegistersAHookAtThePublicAddressWithItsOwnSecret() throws Exception {
        TriggerService.Status status = subscribe("octo/app");
        assertEquals("ACTIVE", status.status());
        TriggerSubscription s = db.values().iterator().next();
        assertEquals("hook_77", s.getExternalId());
        String secret = cipher.decrypt(s.getSecret()).get("secret");
        verify(github).register(same(s), eq(Map.of("access_token", "gho_1")), eq("https://hooks.example.com/hooks/github/" + s.getId()), eq(secret));
        assertEquals(64, secret.length());
        assertFalse(s.getSecret().contains(secret), "stored encrypted");
    }

    @Test
    void savingTheSameTriggerAgainChangesNothingButANewRepositoryReplacesTheHook() throws Exception {
        subscribe("octo/app");
        subscribe("octo/app");
        verify(github, times(1)).register(any(), any(), any(), any());

        subscribe("octo/other");
        verify(github).unregister(argThat(s -> "hook_77".equals(s.getExternalId())), any());
        assertEquals(1, db.size());
        assertEquals(Map.of("repository", "octo/other"), db.values().iterator().next().getConfig());
    }

    @Test
    void setupProblemsComeBackAsTheReason() throws Exception {
        when(github.register(any(), any(), any(), any())).thenThrow(new TriggerSetupException("GitHub didn't let this account add a webhook"));
        TriggerService.Status status = subscribe("octo/app");
        assertEquals("ERROR", status.status());
        assertEquals("GitHub didn't let this account add a webhook", status.error());

        assertTrue(service("").subscribe("wfl_2", "usr_1", "app_github", GitHubTriggers.NEW_ISSUE, "con_1", Map.of())
                .error().contains("TRIGGERS_PUBLIC_URL"));
        assertTrue(service.subscribe("wfl_3", "usr_2", "app_github", GitHubTriggers.NEW_ISSUE, "con_1", Map.of())
                .error().contains("no longer exists"), "someone else's connection");
    }

    @Test
    void appsWithoutAppTriggersNeedNothing() {
        assertNull(service.subscribe("wfl_1", "usr_1", "app_webhook", "trg_webhook_catch", null, Map.of()));
        assertTrue(db.isEmpty());
    }

    @Test
    void turningOffRemovesTheHook() {
        subscribe("octo/app");
        service.unsubscribe("wfl_1");
        verify(github).unregister(any(), eq(Map.of("access_token", "gho_1")));
        assertTrue(db.isEmpty());
    }

    // ---- deliveries ----

    private static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void aSignedDeliveryStartsARunWithTheEventsData() throws Exception {
        subscribe("octo/app");
        TriggerSubscription s = db.values().iterator().next();
        String secret = cipher.decrypt(s.getSecret()).get("secret");
        byte[] body = "{\"action\":\"opened\",\"issue\":{\"number\":5}}".getBytes(StandardCharsets.UTF_8);
        when(github.toTriggerBody(eq(GitHubTriggers.NEW_ISSUE), eq("issues"), any())).thenReturn(Optional.of(Map.of("number", 5)));

        assertEquals(TriggerService.Outcome.STARTED, service.onGitHubDelivery(s.getId(), sign(secret, body), "issues", "d-1", body));
        verify(runs).start("wfl_1", Map.of("number", 5));
        assertNotNull(s.getLastEventAt());

        assertEquals(TriggerService.Outcome.DUPLICATE, service.onGitHubDelivery(s.getId(), sign(secret, body), "issues", "d-1", body),
                "GitHub redelivering the same event starts nothing");
        verify(runs, times(1)).start(any(), any());
    }

    @Test
    void unsignedOrWronglySignedDeliveriesAreRejected() throws Exception {
        subscribe("octo/app");
        String id = db.keySet().iterator().next();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThrows(TriggerService.InvalidDeliveryException.class, () -> service.onGitHubDelivery(id, null, "issues", "d", body));
        assertThrows(TriggerService.InvalidDeliveryException.class, () -> service.onGitHubDelivery(id, sign("guess", body), "issues", "d", body));
        assertThrows(NotFoundException.class, () -> service.onGitHubDelivery("tsub_gone", "sha256=x", "issues", "d", body));
        verifyNoInteractions(runs);
    }

    @Test
    void otherEventsAndPingsStartNothingAndAFailedStartCanBeRedelivered() throws Exception {
        subscribe("octo/app");
        TriggerSubscription s = db.values().iterator().next();
        String secret = cipher.decrypt(s.getSecret()).get("secret");
        byte[] body = "{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8);
        when(github.toTriggerBody(any(), any(), any())).thenReturn(Optional.empty());
        assertEquals(TriggerService.Outcome.IGNORED, service.onGitHubDelivery(s.getId(), sign(secret, body), "ping", "d-p", body));
        assertEquals(TriggerService.Outcome.IGNORED, service.onGitHubDelivery(s.getId(), sign(secret, body), "issues", "d-2", body));

        when(github.toTriggerBody(any(), any(), any())).thenReturn(Optional.of(Map.of("number", 6)));
        when(runs.start(any(), any())).thenThrow(new IllegalStateException("pod-webhooks down"));
        assertThrows(IllegalStateException.class, () -> service.onGitHubDelivery(s.getId(), sign(secret, body), "issues", "d-3", body));
        assertFalse(seenDeliveries.contains("github:d-3"), "forgotten, so GitHub's retry can start it");
    }

    // ---- Slack events ----

    private static String slackSign(String secret, long ts, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(("v0:" + ts + ":").getBytes(StandardCharsets.UTF_8));
        return "v0=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private TriggerSubscription slackSubscription(String id, String workflowId, String connectionId, String team, String via) {
        TriggerSubscription s = new TriggerSubscription();
        s.setId(id);
        s.setWorkflowId(workflowId);
        s.setUserId("usr_1");
        s.setAppId("app_slack");
        s.setTriggerId(SlackTriggers.NEW_MENTION);
        s.setConnectionId(connectionId);
        s.setStatus(TriggerSubscription.STATUS_ACTIVE);
        s.setRoutingKey(team);
        s.setMeta(Map.of("via", via));
        db.put(id, s);
        return s;
    }

    private TriggerService.SlackResult slackEvent(String connectionId, String secret, String payload) throws Exception {
        long ts = System.currentTimeMillis() / 1000;
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        return service.onSlackEvent(connectionId, String.valueOf(ts), slackSign(secret, ts, body), body);
    }

    private static final String MENTION = "{\"type\":\"event_callback\",\"team_id\":\"T1\",\"event_id\":\"Ev1\","
            + "\"event\":{\"type\":\"app_mention\",\"text\":\"hi\"}}";

    @BeforeEach
    void slackSubscriptions() {
        when(subs.findByAppIdAndRoutingKeyAndStatus(eq("app_slack"), any(), eq("ACTIVE"))).thenAnswer(inv -> db.values().stream()
                .filter(s -> s.getAppId().equals("app_slack") && inv.getArgument(1).equals(s.getRoutingKey())).toList());
        when(subs.findByConnectionIdAndStatus(any(), eq("ACTIVE"))).thenAnswer(inv -> db.values().stream()
                .filter(s -> inv.getArgument(0).equals(s.getConnectionId())).toList());
        when(slack.toTriggerBody(any(), any())).thenReturn(Optional.of(Map.of("text", "hi")));
    }

    @Test
    void slacksUrlCheckGetsItsChallengeBack() throws Exception {
        TriggerService.SlackResult r = slackEvent(null, SLACK_SERVER_SECRET, "{\"type\":\"url_verification\",\"challenge\":\"abc\"}");
        assertEquals("abc", r.challenge());
        assertThrows(TriggerService.InvalidDeliveryException.class,
                () -> slackEvent(null, "guess", "{\"type\":\"url_verification\",\"challenge\":\"abc\"}"));
    }

    @Test
    void anEventForTheServersAppStartsEveryMatchingWorkflowInThatWorkspaceOnce() throws Exception {
        slackSubscription("tsub_a", "wfl_a", "con_a", "T1", "server");
        slackSubscription("tsub_b", "wfl_b", "con_b", "T1", "server");
        slackSubscription("tsub_other", "wfl_other", "con_c", "T2", "server");
        slackSubscription("tsub_own", "wfl_own", "con_d", "T1", "own");

        assertEquals(TriggerService.Outcome.STARTED, slackEvent(null, SLACK_SERVER_SECRET, MENTION).outcome());
        verify(runs).start("wfl_a", Map.of("text", "hi"));
        verify(runs).start("wfl_b", Map.of("text", "hi"));
        verify(runs, times(2)).start(any(), any());

        assertEquals(TriggerService.Outcome.DUPLICATE, slackEvent(null, SLACK_SERVER_SECRET, MENTION).outcome(),
                "Slack retrying the event starts nothing again");
        verify(runs, times(2)).start(any(), any());
    }

    @Test
    void anEventForAUsersOwnAppIsCheckedWithTheConnectionsSigningSecret() throws Exception {
        Connection own = new Connection();
        own.setId("con_own");
        own.setAppId("app_slack");
        own.setAuthType(Connection.AUTH_TOKEN);
        own.setCredentials(cipher.encrypt(Map.of("token", "xoxb-own", "signingSecret", "own-secret")));
        when(connections.findById("con_own")).thenReturn(Optional.of(own));
        slackSubscription("tsub_own", "wfl_own", "con_own", "T1", "own");

        assertThrows(TriggerService.InvalidDeliveryException.class, () -> slackEvent("con_own", SLACK_SERVER_SECRET, MENTION));
        assertEquals(TriggerService.Outcome.STARTED, slackEvent("con_own", "own-secret", MENTION).outcome());
        verify(runs).start("wfl_own", Map.of("text", "hi"));
        assertThrows(NotFoundException.class, () -> slackEvent("con_1", "own-secret", MENTION), "not a Slack connection");
    }

    @Test
    void ifOneWorkflowCantStartSlackRetriesAndOnlyThatOneStartsAgain() throws Exception {
        slackSubscription("tsub_a", "wfl_a", "con_a", "T1", "server");
        slackSubscription("tsub_b", "wfl_b", "con_b", "T1", "server");
        when(runs.start(eq("wfl_b"), any())).thenThrow(new IllegalStateException("pod-webhooks down")).thenReturn("exn_1");

        assertThrows(IllegalStateException.class, () -> slackEvent(null, SLACK_SERVER_SECRET, MENTION));
        assertEquals(TriggerService.Outcome.STARTED, slackEvent(null, SLACK_SERVER_SECRET, MENTION).outcome());
        verify(runs, times(1)).start(eq("wfl_a"), any());
        verify(runs, times(2)).start(eq("wfl_b"), any());
    }
}
