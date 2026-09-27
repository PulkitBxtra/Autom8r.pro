package com.bxtralabs.pod.processor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

// How often and how long to wait before retrying a step that failed with a temporary error.
// Exponential backoff: base, 2x base, 4x base ... capped at max, with +-jitter so many steps
// that failed together (e.g. an API outage) don't all retry at the same instant.
@Component
public class RetryPolicy {

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double jitter;

    @Autowired
    public RetryPolicy(@Value("${steps.max-attempts:3}") int maxAttempts,
                       @Value("${steps.retry-base-ms:2000}") long baseDelayMs,
                       @Value("${steps.retry-max-ms:300000}") long maxDelayMs) {
        this(maxAttempts, baseDelayMs, maxDelayMs, 0.2);
    }

    RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs, double jitter) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.jitter = jitter;
    }

    // attemptsMade: how many attempts have run so far (StepRun.attempt).
    // Returns when to try again, or null if the step is out of attempts.
    public Long nextAttemptAt(int attemptsMade, long now) {
        if (attemptsMade >= maxAttempts) {
            return null;
        }
        return now + delayMs(attemptsMade);
    }

    long delayMs(int attemptsMade) {
        // 1 attempt made -> base, 2 -> 2x base, 3 -> 4x base ...
        int exponent = Math.min(Math.max(attemptsMade - 1, 0), 30);
        long delay = Math.min(baseDelayMs << exponent, maxDelayMs);
        if (jitter > 0) {
            double factor = 1 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
            delay = Math.round(delay * factor);
        }
        return Math.max(delay, 0);
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
