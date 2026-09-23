package com.actilazion.aries_transaction.payment.application;

import com.actilazion.aries_transaction.common.exception.RateLimitExceededException;
import com.actilazion.aries_transaction.common.redis.AuthRateLimitStore;
import com.actilazion.aries_transaction.common.redis.SecurityKeyHasher;
import com.actilazion.aries_transaction.config.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class QrRequestProtection {
    private final AuthRateLimitStore store;
    private final SecurityKeyHasher hasher;
    private final ClientIpResolver clientIpResolver;

    public void check(HttpServletRequest request, UUID callerId) {
        check("ip", clientIpResolver.resolve(request), 120);
        check("user", callerId.toString(), 30);
    }

    private void check(String dimension, String value, int limit) {
        var result = store.increment("payment-qr:" + dimension + ":" + hasher.hash(value), limit, Duration.ofMinutes(1));
        if (!result.allowed()) throw new RateLimitExceededException(result.retryAfterSeconds());
    }
}
