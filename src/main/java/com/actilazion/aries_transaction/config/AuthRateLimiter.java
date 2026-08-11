package com.actilazion.aries_transaction.config;

import com.actilazion.aries_transaction.common.exception.RateLimitExceededException;
import com.actilazion.aries_transaction.common.redis.AuthRateLimitStore;
import com.actilazion.aries_transaction.common.redis.SecurityKeyHasher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

@Component
public class AuthRateLimiter {
    private final AuthRateLimitConfig config;
    private final AuthRateLimitStore store;
    private final SecurityKeyHasher keyHasher;
    private final ClientIpResolver clientIpResolver;

    public AuthRateLimiter(
            AuthRateLimitConfig config,
            AuthRateLimitStore store,
            SecurityKeyHasher keyHasher,
            ClientIpResolver clientIpResolver
    ) {
        this.config = config;
        this.store = store;
        this.keyHasher = keyHasher;
        this.clientIpResolver = clientIpResolver;
    }

    public void check(String endpoint, HttpServletRequest request, String identity) {
        Duration window = Duration.ofSeconds(config.getWindowSeconds());
        checkBucket(endpoint + ":ip:" + keyHasher.hash(clientIpResolver.resolve(request)), config.getIpRequests(), window);
        if (identity != null && !identity.isBlank()) {
            String normalizedIdentity = "refresh".equals(endpoint)
                    ? identity.trim()
                    : identity.trim().toLowerCase(Locale.ROOT);
            checkBucket(endpoint + ":identity:" + keyHasher.hash(normalizedIdentity),
                    config.getIdentityRequests(), window);
        }
    }

    private void checkBucket(String key, int limit, Duration window) {
        var decision = store.increment(key, limit, window);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
    }
}
