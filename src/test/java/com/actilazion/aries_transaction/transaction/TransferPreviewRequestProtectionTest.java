package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.common.exception.RateLimitExceededException;
import com.actilazion.aries_transaction.common.redis.InMemoryAuthRateLimitStore;
import com.actilazion.aries_transaction.common.redis.SecurityKeyHasher;
import com.actilazion.aries_transaction.config.AuthRateLimitConfig;
import com.actilazion.aries_transaction.config.ClientIpResolver;
import com.actilazion.aries_transaction.config.RedisEphemeralProperties;
import com.actilazion.aries_transaction.transaction.application.TransferPreviewProperties;
import com.actilazion.aries_transaction.transaction.application.TransferPreviewRequestProtection;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferPreviewRequestProtectionTest {
    @Test
    void execute_limitsUserAndReturnsRetryAfter() {
        TransferPreviewProperties properties = properties();
        properties.setUserRequests(1);
        TransferPreviewRequestProtection protection = protection(properties);
        MockHttpServletRequest request = request("192.0.2.10");
        UUID userId = UUID.randomUUID();

        assertThat(protection.execute(request, userId, () -> "allowed")).isEqualTo("allowed");

        assertThatThrownBy(() -> protection.execute(request, userId, () -> "blocked"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(error -> assertThat(((RateLimitExceededException) error).getRetryAfterSeconds())
                        .isPositive());
    }

    @Test
    void execute_appliesMinimumResponseFloorToSuccessAndFailure() {
        TransferPreviewProperties properties = properties();
        properties.setMinimumResponseMillis(15);
        TransferPreviewRequestProtection protection = protection(properties);
        MockHttpServletRequest request = request("192.0.2.11");

        long successStarted = System.nanoTime();
        protection.execute(request, UUID.randomUUID(), () -> "ok");
        long successMillis = (System.nanoTime() - successStarted) / 1_000_000;

        long failureStarted = System.nanoTime();
        assertThatThrownBy(() -> protection.execute(request, UUID.randomUUID(), () -> {
            throw new IllegalArgumentException("unavailable");
        })).isInstanceOf(IllegalArgumentException.class);
        long failureMillis = (System.nanoTime() - failureStarted) / 1_000_000;

        assertThat(successMillis).isGreaterThanOrEqualTo(10);
        assertThat(failureMillis).isGreaterThanOrEqualTo(10);
    }

    private TransferPreviewRequestProtection protection(TransferPreviewProperties properties) {
        RedisEphemeralProperties ephemeralProperties = new RedisEphemeralProperties();
        ephemeralProperties.setKeyHashSecret("preview-protection-test-secret");
        AuthRateLimitConfig ipConfig = new AuthRateLimitConfig();
        return new TransferPreviewRequestProtection(
                properties,
                new InMemoryAuthRateLimitStore(),
                new SecurityKeyHasher(ephemeralProperties),
                new ClientIpResolver(ipConfig)
        );
    }

    private TransferPreviewProperties properties() {
        TransferPreviewProperties properties = new TransferPreviewProperties();
        properties.setRateLimitWindowSeconds(60);
        properties.setUserRequests(10);
        properties.setIpRequests(10);
        properties.setMinimumResponseMillis(0);
        return properties;
    }

    private MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
