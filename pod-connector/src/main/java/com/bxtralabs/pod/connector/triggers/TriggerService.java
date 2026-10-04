package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.common.NotFoundException;
import com.bxtralabs.pod.connector.connections.ConnectorRegistry;
import com.bxtralabs.pod.connector.connections.CredentialCipher;
import com.bxtralabs.pod.connector.connections.TokenService;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.model.TriggerSubscription;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import com.bxtralabs.pod.connector.repository.TriggerDeliveryRepository;
import com.bxtralabs.pod.connector.repository.TriggerSubscriptionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

// Keeps each active workflow's app trigger registered with the app, and turns the app's
// deliveries into runs. pod-backend calls subscribe when a workflow is turned on (or saved again
// while on) and unsubscribe when it's turned off.
@Service
public class TriggerService {

    // eventsUrl: where the user must point their own app's events (Slack with a bot token), else null.
    public record Status(String status, String error, Long lastEventAt, String appId, String triggerId, String eventsUrl) {
    }

    private final TriggerSubscriptionRepository subscriptions;
    private final TriggerDeliveryRepository deliveries;
    private final ConnectionRepository connections;
    private final TokenService tokens;
    private final CredentialCipher cipher;
    private final List<AppTriggerRegistrar> registrars;
    private final GitHubTriggers github;
    private final SlackTriggers slack;
    private final StripeTriggers stripe;
    private final RunStarter runs;
    private final JsonMapper jsonMapper;
    private final String publicUrl;
    private final String slackSigningSecret;
    private final SecureRandom random = new SecureRandom();

    public TriggerService(TriggerSubscriptionRepository subscriptions, TriggerDeliveryRepository deliveries,
                          ConnectionRepository connections, TokenService tokens, CredentialCipher cipher,
                          List<AppTriggerRegistrar> registrars, GitHubTriggers github, SlackTriggers slack,
                          StripeTriggers stripe, RunStarter runs,
                          JsonMapper jsonMapper, @Value("${triggers.public-url:}") String publicUrl,
                          @Value("${connectors.slack.signing-secret:}") String slackSigningSecret) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.connections = connections;
        this.tokens = tokens;
        this.cipher = cipher;
        this.registrars = registrars;
        this.github = github;
        this.slack = slack;
        this.stripe = stripe;
        this.runs = runs;
        this.jsonMapper = jsonMapper;
        this.publicUrl = publicUrl == null ? "" : publicUrl.trim().replaceAll("/+$", "");
        this.slackSigningSecret = slackSigningSecret == null ? "" : slackSigningSecret.trim();
    }

    // Registers the workflow's trigger with its app (replacing what was registered before, unless
    // it's unchanged). Apps without app triggers (Webhook, Logic) need nothing and get null.
    public Status subscribe(String workflowId, String userId, String appId, String triggerId, String connectionId,
                            Map<String, Object> config) {
        AppTriggerRegistrar registrar = registrars.stream().filter(r -> r.supports(appId)).findFirst().orElse(null);
        Optional<TriggerSubscription> existing = subscriptions.findByWorkflowId(workflowId);
        if (registrar == null) {
            existing.ifPresent(this::remove);
            return null;
        }
        Map<String, Object> cfg = config == null ? Map.of() : config;
        if (existing.isPresent()) {
            TriggerSubscription e = existing.get();
            if (TriggerSubscription.STATUS_ACTIVE.equals(e.getStatus()) && e.getAppId().equals(appId)
                    && e.getTriggerId().equals(triggerId) && Objects.equals(e.getConnectionId(), connectionId)
                    && Objects.equals(e.getConfig(), cfg)) {
                return status(e); // already listening for exactly this
            }
            remove(e);
        }

        TriggerSubscription s = new TriggerSubscription();
        s.setWorkflowId(workflowId);
        s.setUserId(userId);
        s.setAppId(appId);
        s.setTriggerId(triggerId);
        s.setConnectionId(connectionId);
        s.setConfig(new LinkedHashMap<>(cfg));
        String secret = randomSecret();
        s.setSecret(cipher.encrypt(Map.of("secret", secret)));
        s.setStatus(TriggerSubscription.STATUS_ERROR);
        s = subscriptions.save(s); // the id goes into the hook URL
        try {
            if (publicUrl.isEmpty()) {
                throw new TriggerSetupException("App triggers need a public address for Autom8r (TRIGGERS_PUBLIC_URL isn't set)");
            }
            Connection c = connections.findById(connectionId == null ? "" : connectionId)
                    .filter(found -> found.getUserId().equals(userId) && found.getAppId().equals(appId))
                    .orElseThrow(() -> new TriggerSetupException("The trigger's account no longer exists; choose another"));
            Map<String, String> creds = tokens.getValidCredentials(c.getId());
            String hookUrl = publicUrl + "/hooks/" + appId.replaceFirst("^app_", "") + "/" + s.getId();
            AppTriggerRegistrar.Registration registration = registrar.register(s, creds, hookUrl, secret);
            s.setExternalId(registration.externalId());
            if (registration.secret() != null) {
                s.setSecret(cipher.encrypt(Map.of("secret", registration.secret())));
            }
            s.setStatus(TriggerSubscription.STATUS_ACTIVE);
            s.setLastError(null);
        } catch (TriggerSetupException e) {
            s.setLastError(e.getMessage());
        } catch (RuntimeException e) {
            // e.g. the connection needs reconnecting (ConnectionNeedsReauthException)
            s.setLastError(e.getMessage());
        }
        return status(subscriptions.save(s));
    }

    public void unsubscribe(String workflowId) {
        subscriptions.findByWorkflowId(workflowId).ifPresent(this::remove);
    }

    // For the workflow's owner (null if nothing is registered).
    public Status statusFor(String workflowId, String userId) {
        return subscriptions.findByWorkflowId(workflowId)
                .filter(s -> s.getUserId().equals(userId))
                .map(TriggerService::status)
                .orElse(null);
    }

    private void remove(TriggerSubscription s) {
        registrars.stream().filter(r -> r.supports(s.getAppId())).findFirst().ifPresent(r -> {
            try {
                if (s.getExternalId() != null && s.getConnectionId() != null) {
                    r.unregister(s, tokens.getValidCredentials(s.getConnectionId()));
                }
            } catch (RuntimeException e) {
                System.out.println("Couldn't unregister " + s + ": " + e.getMessage());
            }
        });
        subscriptions.delete(s);
    }

    private static Status status(TriggerSubscription s) {
        Object eventsUrl = s.getMeta() == null ? null : s.getMeta().get("eventsUrl");
        return new Status(s.getStatus(), s.getLastError(), s.getLastEventAt(), s.getAppId(), s.getTriggerId(),
                eventsUrl == null ? null : String.valueOf(eventsUrl));
    }

    // ---- deliveries ----

    public enum Outcome { STARTED, IGNORED, DUPLICATE }

    // A GitHub webhook delivery for a subscription. Rejects anything not signed with its secret.
    public Outcome onGitHubDelivery(String subscriptionId, String signature, String event, String deliveryId, byte[] body) {
        TriggerSubscription s = subscriptions.findById(subscriptionId)
                .filter(found -> "app_github".equals(found.getAppId()))
                .orElseThrow(() -> new NotFoundException("No such trigger"));
        String secret = cipher.decrypt(s.getSecret()).get("secret");
        if (!validGitHubSignature(secret, body, signature)) {
            throw new InvalidDeliveryException();
        }
        if ("ping".equals(event)) {
            return Outcome.IGNORED; // GitHub's hello when the hook is created
        }
        Map<?, ?> payload = jsonMapper.readValue(body, Map.class);
        Optional<Map<String, Object>> triggerBody = github.toTriggerBody(s.getTriggerId(), event, payload);
        if (triggerBody.isEmpty()) {
            return Outcome.IGNORED;
        }
        if (deliveryId != null && deliveries.recordNew("github:" + deliveryId, s.getId(), System.currentTimeMillis()) == 0) {
            return Outcome.DUPLICATE;
        }
        try {
            runs.start(s.getWorkflowId(), triggerBody.get());
        } catch (RuntimeException e) {
            // Let GitHub redeliver it: forget we saw it.
            if (deliveryId != null) deliveries.deleteById("github:" + deliveryId);
            throw e;
        }
        s.setLastEventAt(System.currentTimeMillis());
        subscriptions.save(s);
        return Outcome.STARTED;
    }

    // A Stripe webhook event for a subscription. Rejects anything not signed with the endpoint's
    // secret within the last 5 minutes.
    public Outcome onStripeEvent(String subscriptionId, String signature, byte[] body) {
        TriggerSubscription s = subscriptions.findById(subscriptionId)
                .filter(found -> "app_stripe".equals(found.getAppId()))
                .orElseThrow(() -> new NotFoundException("No such trigger"));
        String secret = cipher.decrypt(s.getSecret()).get("secret");
        if (!validStripeSignature(secret, body, signature, System.currentTimeMillis())) {
            throw new InvalidDeliveryException();
        }
        Map<?, ?> event = jsonMapper.readValue(body, Map.class);
        Optional<Map<String, Object>> triggerBody = stripe.toTriggerBody(s.getTriggerId(), event);
        if (triggerBody.isEmpty()) {
            return Outcome.IGNORED;
        }
        String key = "stripe:" + event.get("id") + ":" + s.getId();
        if (deliveries.recordNew(key, s.getId(), System.currentTimeMillis()) == 0) {
            return Outcome.DUPLICATE;
        }
        try {
            runs.start(s.getWorkflowId(), triggerBody.get());
        } catch (RuntimeException e) {
            // Let Stripe retry it: forget we saw it.
            deliveries.deleteById(key);
            throw e;
        }
        s.setLastEventAt(System.currentTimeMillis());
        subscriptions.save(s);
        return Outcome.STARTED;
    }

    // Stripe-Signature: "t=<unix seconds>,v1=<hex>[,v1=...]" where v1 = HMAC-SHA256(secret,
    // t + "." + raw body); any v1 may match (Stripe sends several while a secret is being rolled).
    // t must be within 5 minutes, so a captured request can't be replayed later.
    static boolean validStripeSignature(String secret, byte[] body, String header, long nowMs) {
        if (secret == null || secret.isBlank() || header == null) return false;
        Long ts = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if ("t".equals(kv[0])) {
                try {
                    ts = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if ("v1".equals(kv[0])) {
                signatures.add(kv[1]);
            }
        }
        if (ts == null || signatures.isEmpty() || Math.abs(nowMs / 1000 - ts) > 300) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((ts + ".").getBytes(StandardCharsets.UTF_8));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.UTF_8);
            boolean match = false;
            for (String sig : signatures) {
                match |= MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.UTF_8));
            }
            return match;
        } catch (Exception e) {
            return false;
        }
    }

    // What Slack gets back: the challenge for its URL check, or the outcome of an event.
    public record SlackResult(String challenge, Outcome outcome) {
    }

    // A Slack Events API request. connectionId null: this server's Slack app (/hooks/slack), whose
    // events are matched by workspace; else a connection's own app, matched by connection. Rejects
    // anything not signed with that app's signing secret within the last 5 minutes.
    public SlackResult onSlackEvent(String connectionId, String timestamp, String signature, byte[] body) {
        String signingSecret;
        if (connectionId == null) {
            signingSecret = slackSigningSecret;
        } else {
            Connection c = connections.findById(connectionId)
                    .filter(found -> "app_slack".equals(found.getAppId()) && Connection.AUTH_TOKEN.equals(found.getAuthType()))
                    .orElseThrow(() -> new NotFoundException("No such Slack connection"));
            signingSecret = cipher.decrypt(c.getCredentials()).get(ConnectorRegistry.SLACK_SIGNING_SECRET);
        }
        if (!validSlackSignature(signingSecret, timestamp, body, signature, System.currentTimeMillis())) {
            throw new InvalidDeliveryException();
        }
        Map<?, ?> payload = jsonMapper.readValue(body, Map.class);
        if ("url_verification".equals(payload.get("type"))) {
            return new SlackResult(String.valueOf(payload.get("challenge")), Outcome.IGNORED);
        }
        if (!"event_callback".equals(payload.get("type")) || !(payload.get("event") instanceof Map<?, ?> event)) {
            return new SlackResult(null, Outcome.IGNORED);
        }
        List<TriggerSubscription> candidates = connectionId == null
                ? subscriptions.findByAppIdAndRoutingKeyAndStatus("app_slack", String.valueOf(payload.get("team_id")),
                        TriggerSubscription.STATUS_ACTIVE).stream()
                        .filter(s -> s.getMeta() != null && SlackTriggers.VIA_SERVER.equals(s.getMeta().get("via"))).toList()
                : subscriptions.findByConnectionIdAndStatus(connectionId, TriggerSubscription.STATUS_ACTIVE);
        String eventId = String.valueOf(payload.get("event_id"));
        Outcome outcome = Outcome.IGNORED;
        RuntimeException failure = null;
        for (TriggerSubscription s : candidates) {
            Optional<Map<String, Object>> triggerBody = slack.toTriggerBody(s, event);
            if (triggerBody.isEmpty()) continue;
            String key = "slack:" + eventId + ":" + s.getId();
            if (deliveries.recordNew(key, s.getId(), System.currentTimeMillis()) == 0) {
                if (outcome == Outcome.IGNORED) outcome = Outcome.DUPLICATE;
                continue;
            }
            try {
                runs.start(s.getWorkflowId(), triggerBody.get());
            } catch (RuntimeException e) {
                // Let Slack retry it: forget we saw it. The others that started stay recorded.
                deliveries.deleteById(key);
                failure = e;
                continue;
            }
            s.setLastEventAt(System.currentTimeMillis());
            subscriptions.save(s);
            outcome = Outcome.STARTED;
        }
        if (failure != null) {
            throw failure;
        }
        return new SlackResult(null, outcome);
    }

    // X-Slack-Signature: "v0=" + hex(HMAC-SHA256(signing secret, "v0:" + timestamp + ":" + raw body)),
    // with X-Slack-Request-Timestamp within 5 minutes (so a captured request can't be replayed later).
    static boolean validSlackSignature(String secret, String timestamp, byte[] body, String header, long nowMs) {
        if (secret == null || secret.isBlank() || timestamp == null || header == null || !header.startsWith("v0=")) {
            return false;
        }
        try {
            long ts = Long.parseLong(timestamp.trim());
            if (Math.abs(nowMs / 1000 - ts) > 300) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("v0:" + ts + ":").getBytes(StandardCharsets.UTF_8));
            String expected = "v0=" + HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    // X-Hub-Signature-256: "sha256=" + hex(HMAC-SHA256(secret, raw body)), compared in constant time.
    static boolean validGitHubSignature(String secret, byte[] body, String header) {
        if (header == null || !header.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${triggers.delivery-prune-interval-ms:3600000}")
    public void pruneDeliveries() {
        deliveries.deleteOlderThan(System.currentTimeMillis() - 7L * 24 * 3600_000);
    }

    private String randomSecret() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static class InvalidDeliveryException extends RuntimeException {
        public InvalidDeliveryException() {
            super("Signature doesn't match");
        }
    }
}
