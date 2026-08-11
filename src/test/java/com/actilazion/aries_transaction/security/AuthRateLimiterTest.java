package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.common.exception.RateLimitExceededException;
import com.actilazion.aries_transaction.config.AuthRateLimitConfig;
import com.actilazion.aries_transaction.config.AuthRateLimiter;
import com.actilazion.aries_transaction.config.ClientIpResolver;
import com.actilazion.aries_transaction.config.RedisEphemeralProperties;
import com.actilazion.aries_transaction.common.redis.InMemoryAuthRateLimitStore;
import com.actilazion.aries_transaction.common.redis.SecurityKeyHasher;
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
        RedisEphemeralProperties properties = new RedisEphemeralProperties();
        properties.setKeyHashSecret("test-secret");
        AuthRateLimiter limiter = new AuthRateLimiter(
                config,
                new InMemoryAuthRateLimitStore(),
                new SecurityKeyHasher(properties),
                new ClientIpResolver(config)
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");

        limiter.check("login", request, "user@example.test");

        assertThatThrownBy(() -> limiter.check("login", request, "user@example.test"))
                .isInstanceOf(RateLimitExceededException.class);
    }
}
