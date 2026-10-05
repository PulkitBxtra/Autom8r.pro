package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.connections.CredentialCipher;
import com.bxtralabs.pod.connector.connections.TokenService;
import com.bxtralabs.pod.connector.model.TriggerSubscription;
import com.bxtralabs.pod.connector.repository.TriggerDeliveryRepository;
import com.bxtralabs.pod.connector.repository.TriggerSubscriptionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Polls the active subscriptions of apps without webhooks (PollingTriggers: Gmail, Google Sheets)
// about once a minute, and starts a run for each new event. Subscriptions are claimed in batches,
// their rows locked (FOR UPDATE SKIP LOCKED) so several pod-connector instances never poll one at
// once. Its checkpoint (meta) is saved only after every run it found has started: if a
// start fails, the next poll sees the same events again and the ones that did start are skipped
// (de-duplicated on the event key). A poll that fails (the app down, the account needing
// reconnecting) is recorded as the subscription's lastError and retried next time.
@Component
public class TriggerPoller {

    static final int BATCH_SIZE = 20;

    private final TriggerSubscriptionRepository subscriptions;
    private final TriggerDeliveryRepository deliveries;
    private final TokenService tokens;
    private final CredentialCipher cipher;
    private final List<PollingTriggers> pollers;
    private final RunStarter runs;
    private final TransactionTemplate transactions;
    private final long intervalMs;

    public TriggerPoller(TriggerSubscriptionRepository subscriptions, TriggerDeliveryRepository deliveries, TokenService tokens,
                         CredentialCipher cipher, List<PollingTriggers> pollers, RunStarter runs, TransactionTemplate transactions,
                         @Value("${triggers.poll-interval-ms:60000}") long intervalMs) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.tokens = tokens;
        this.cipher = cipher;
        this.pollers = pollers;
        this.runs = runs;
        this.transactions = transactions;
        this.intervalMs = intervalMs;
    }

    @Scheduled(fixedDelayString = "${triggers.poll-interval-ms:60000}", initialDelayString = "${triggers.poll-initial-delay-ms:15000}")
    public void pollDue() {
        if (!cipher.isConfigured() || pollers.isEmpty()) return;
        List<String> apps = List.of("app_gmail", "app_sheets");
        long runStartedAt = System.currentTimeMillis();
        int[] claimed = new int[1];
        do {
            // One transaction per batch: its rows stay locked until each is polled and saved.
            // Due: not polled since this run started, nor within the last half interval.
            long before = Math.min(runStartedAt, System.currentTimeMillis() - intervalMs / 2);
            transactions.executeWithoutResult(tx -> {
                List<TriggerSubscription> due = subscriptions.lockDueForPoll(apps, before, BATCH_SIZE);
                claimed[0] = due.size();
                due.forEach(this::pollOne);
            });
        } while (claimed[0] == BATCH_SIZE);
    }

    void pollOne(TriggerSubscription s) {
        PollingTriggers poller = pollers.stream().filter(p -> p.supports(s.getAppId())).findFirst().orElse(null);
        Map<String, Object> meta = new LinkedHashMap<>(s.getMeta() == null ? Map.of() : s.getMeta());
        meta.put("polledAt", System.currentTimeMillis()); // marks it polled even when nothing changed
        if (poller == null) {
            s.setMeta(meta);
            subscriptions.save(s);
            return;
        }
        try {
            PollingTriggers.Poll poll = poller.poll(s, tokens.getValidCredentials(s.getConnectionId()));
            boolean started = false;
            for (PollingTriggers.Event event : poll.events()) {
                String key = event.key() + ":" + s.getId();
                if (deliveries.recordNew(key, s.getId(), System.currentTimeMillis()) == 0) continue;
                try {
                    runs.start(s.getWorkflowId(), event.body());
                    started = true;
                } catch (RuntimeException e) {
                    // Forget it and keep the old checkpoint: the next poll finds it again.
                    deliveries.deleteById(key);
                    throw e;
                }
            }
            meta.putAll(poll.meta());
            meta.put("polledAt", System.currentTimeMillis());
            if (started) s.setLastEventAt(System.currentTimeMillis());
            s.setLastError(null);
        } catch (TriggerSetupException | RuntimeException e) {
            s.setLastError(e.getMessage());
        }
        s.setMeta(meta);
        subscriptions.save(s);
    }
}
