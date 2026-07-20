package com.acme.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "gateway.deletion")
public record DeletionProperties(
        int maxRetries,
        Duration backoffInitial,
        double backoffMultiplier,
        Duration backoffCap,
        Duration reconcileInterval,
        int reconcileBatchSize) {

    /** Exponential backoff: initial * multiplier^(attempts-1), capped. */
    public Duration backoffFor(int attemptsSoFar) {
        double factor = Math.pow(backoffMultiplier, Math.max(0, attemptsSoFar - 1));
        long millis = (long) (backoffInitial.toMillis() * factor);
        return Duration.ofMillis(Math.min(millis, backoffCap.toMillis()));
    }
}
