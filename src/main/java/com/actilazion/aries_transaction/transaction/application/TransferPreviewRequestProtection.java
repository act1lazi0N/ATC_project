package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.common.exception.RateLimitExceededException;
import com.actilazion.aries_transaction.common.redis.AuthRateLimitStore;
import com.actilazion.aries_transaction.common.redis.SecurityKeyHasher;
import com.actilazion.aries_transaction.config.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class TransferPreviewRequestProtection {
    private final TransferPreviewProperties properties;
    private final AuthRateLimitStore rateLimitStore;
    private final SecurityKeyHasher keyHasher;
    private final ClientIpResolver clientIpResolver;

    public <T> T execute(HttpServletRequest request, UUID userId, Supplier<T> action) {
        Duration window = Duration.ofSeconds(properties.getRateLimitWindowSeconds());
        checkBucket("ip", clientIpResolver.resolve(request), properties.getIpRequests(), window);
        checkBucket("user", userId.toString(), properties.getUserRequests(), window);

        long startedAt = System.nanoTime();
        try {
            return action.get();
        } finally {
            waitForMinimumResponseTime(startedAt);
        }
    }

    private void checkBucket(String dimension, String value, int limit, Duration window) {
        String key = "transfer-preview:" + dimension + ":" + keyHasher.hash(value);
        AuthRateLimitStore.RateLimitDecision decision = rateLimitStore.increment(key, limit, window);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
    }

    private void waitForMinimumResponseTime(long startedAt) {
        long minimumNanos = TimeUnit.MILLISECONDS.toNanos(properties.getMinimumResponseMillis());
        long remaining = minimumNanos - (System.nanoTime() - startedAt);
        while (remaining > 0 && !Thread.currentThread().isInterrupted()) {
            LockSupport.parkNanos(remaining);
            remaining = minimumNanos - (System.nanoTime() - startedAt);
        }
    }
}
