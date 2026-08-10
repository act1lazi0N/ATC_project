package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.common.exception.RateLimitExceededException;
import com.actilazion.aries_transaction.config.AuthRateLimitConfig;
import com.actilazion.aries_transaction.config.AuthRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimiterTest {
    @Test
    void limitsBothIpAndIdentityBuckets() {
        AuthRateLimitConfig config = new AuthRateLimitConfig();
        config.setWindowSeconds(60);
        config.setIpRequests(2);
        config.setIdentityRequests(1);
        AuthRateLimiter limiter = new AuthRateLimiter(config);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");

        limiter.check("login", request, "user@example.test");

        assertThatThrownBy(() -> limiter.check("login", request, "user@example.test"))
                .isInstanceOf(RateLimitExceededException.class);
    }
}
