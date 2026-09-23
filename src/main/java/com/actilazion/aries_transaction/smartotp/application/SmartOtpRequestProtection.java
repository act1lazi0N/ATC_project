package com.actilazion.aries_transaction.smartotp.application;

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
public class SmartOtpRequestProtection {
    private final AuthRateLimitStore store;
    private final SecurityKeyHasher hasher;
    private final ClientIpResolver ips;
    public void check(HttpServletRequest request, UUID user) {
        check("ip", ips.resolve(request), 60);
        check("user", user.toString(), 20);
    }
    private void check(String dimension, String value, int limit) {
        var result = store.increment("smart-otp:" + dimension + ":" + hasher.hash(value), limit, Duration.ofMinutes(1));
        if (!result.allowed()) throw new RateLimitExceededException(result.retryAfterSeconds());
    }
}
