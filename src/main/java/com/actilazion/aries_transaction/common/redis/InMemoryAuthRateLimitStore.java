package com.actilazion.aries_transaction.common.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(prefix = "security.ephemeral", name = "enabled", havingValue = "false")
public class InMemoryAuthRateLimitStore implements AuthRateLimitStore {
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public RateLimitDecision increment(String key, int limit, Duration window) {
        Instant now = Instant.now();
        Window current = windows.compute(key, (ignored, existing) -> {
            if (existing == null || existing.expiresAt().isBefore(now)) {
                return new Window(now.plusSeconds(window.getSeconds()), 1);
            }
            return new Window(existing.expiresAt(), existing.count() + 1);
        });
        long retryAfter = Math.max(1, Duration.between(now, current.expiresAt()).toSeconds());
        return new RateLimitDecision(current.count() <= limit, current.count(), retryAfter);
    }

    private record Window(Instant expiresAt, long count) {
    }
}
