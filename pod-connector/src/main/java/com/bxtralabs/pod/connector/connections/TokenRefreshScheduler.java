package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

// Keeps OAuth access tokens fresh ahead of time: every minute, refreshes active connections
// whose token expires within the next 5 minutes, so steps never wait on a refresh or find an
// expired token. Rows are claimed with FOR UPDATE SKIP LOCKED, so several pod-connector
// instances split the work and a connection being refreshed on demand right now is skipped
// (TokenService.getValidCredentials holds the same row lock).
@Component
public class TokenRefreshScheduler {

    static final int BATCH_SIZE = 50;

    private final ConnectionRepository repository;
    private final TokenService tokenService;
    private final CredentialCipher cipher;
    private final TransactionTemplate transactionTemplate;
    private final long windowMs;
    private final long marginMs;

    public TokenRefreshScheduler(ConnectionRepository repository, TokenService tokenService, CredentialCipher cipher,
                                 TransactionTemplate transactionTemplate,
                                 @Value("${connections.refresh-window-ms:300000}") long windowMs,
                                 @Value("${connections.refresh-margin-ms:60000}") long marginMs) {
        this.repository = repository;
        this.tokenService = tokenService;
        this.cipher = cipher;
        this.transactionTemplate = transactionTemplate;
        this.windowMs = windowMs;
        this.marginMs = marginMs;
    }

    @Scheduled(fixedDelayString = "${connections.refresh-interval-ms:60000}")
    public void refreshExpiring() {
        if (!cipher.isConfigured()) {
            return;
        }
        long runStartedAt = System.currentTimeMillis();
        int[] claimed = new int[1];
        do {
            // One transaction per batch: its rows stay locked until the batch is saved.
            transactionTemplate.executeWithoutResult(tx -> {
                long now = System.currentTimeMillis();
                List<Connection> due = repository.lockDueForRefresh(now + windowMs, now, runStartedAt,
                        now - windowMs / 2, now + marginMs, BATCH_SIZE);
                claimed[0] = due.size();
                for (Connection c : due) {
                    try {
                        tokenService.refreshLocked(c, cipher.decrypt(c.getCredentials()), now);
                    } catch (RuntimeException e) {
                        // Already recorded on the connection (NEEDS_REAUTH or backoff); one bad
                        // connection must not stop the rest of the batch.
                        System.out.println("Token refresh for " + c.getId() + " failed: " + e.getMessage());
                    }
                }
            });
        } while (claimed[0] == BATCH_SIZE);
    }
}
