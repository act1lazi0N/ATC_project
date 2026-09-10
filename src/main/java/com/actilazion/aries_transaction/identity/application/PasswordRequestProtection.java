package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.common.exception.RateLimitExceededException;
import com.actilazion.aries_transaction.common.redis.AuthRateLimitStore;
import com.actilazion.aries_transaction.common.redis.SecurityKeyHasher;
import com.actilazion.aries_transaction.config.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@Component
@RequiredArgsConstructor
public class PasswordRequestProtection {
    private final AccountSecurityProperties properties;
    private final AuthRateLimitStore store;
    private final SecurityKeyHasher hasher;
    private final ClientIpResolver clientIpResolver;

    public void forgot(HttpServletRequest request, String email, Runnable action) {
        properties.requireEnabled();
        String emailHash = hasher.hash(email.trim().toLowerCase(Locale.ROOT));
        Duration window = Duration.ofSeconds(properties.getForgotWindowSeconds());
        check("forgot:ip:" + hasher.hash(clientIpResolver.resolve(request)), properties.getForgotIpRequests(), window);
        check("forgot:email:" + emailHash, properties.getForgotEmailRequests(), window);
        long startedAt = System.nanoTime();
        try {
            // Count unknown and suspended identities too. Cooldown must never reveal eligibility.
            if (store.increment("forgot:cooldown:" + emailHash, 1,
                    Duration.ofSeconds(properties.getResendSeconds())).allowed()) {
                action.run();
            }
        } finally {
            long remaining = TimeUnit.MILLISECONDS.toNanos(properties.getMinimumResponseMillis())
                    - (System.nanoTime() - startedAt);
            while (remaining > 0 && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(remaining);
                remaining = TimeUnit.MILLISECONDS.toNanos(properties.getMinimumResponseMillis())
                        - (System.nanoTime() - startedAt);
            }
        }
    }

    private void check(String key, int limit, Duration window) {
        var decision = store.increment(key, limit, window);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
    }
}
