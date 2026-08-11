package com.actilazion.aries_transaction.common.redis;

import java.time.Duration;

public interface AuthRateLimitStore {
    RateLimitDecision increment(String key, int limit, Duration window);

    record RateLimitDecision(boolean allowed, long count, long retryAfterSeconds) {
    }
}
