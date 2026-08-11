package com.actilazion.aries_transaction.common.redis;

import com.actilazion.aries_transaction.config.RedisKeyNamespace;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "security.ephemeral", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisAuthRateLimitStore implements AuthRateLimitStore {
    private final RedisCounterOperations counterOperations;
    private final RedisKeyNamespace keyNamespace;

    @Override
    public RateLimitDecision increment(String key, int limit, Duration window) {
        RedisCounterOperations.CounterResult result = counterOperations.increment(
                keyNamespace.key("rate-limit", key), window);
        long retryAfter = Math.max(1, (result.ttlMillis() + 999) / 1000);
        return new RateLimitDecision(result.count() <= limit, result.count(), retryAfter);
    }
}
