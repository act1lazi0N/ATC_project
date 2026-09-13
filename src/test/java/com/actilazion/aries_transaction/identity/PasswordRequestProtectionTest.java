package com.actilazion.aries_transaction.identity;

import com.actilazion.aries_transaction.common.exception.RateLimitExceededException;
import com.actilazion.aries_transaction.common.redis.*;
import com.actilazion.aries_transaction.config.*;
import com.actilazion.aries_transaction.identity.application.*;
import com.actilazion.aries_transaction.support.TestSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class PasswordRequestProtectionTest {
    @Test
    void normalizesEmailAndEnforcesCooldownAndLimitBeforeIdentityLookup() {
        AccountSecurityProperties properties = properties();
        PasswordRequestProtection protection = protection(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.1");
        AtomicInteger lookups = new AtomicInteger();
        protection.forgot(request, " Person@TEST.local ", lookups::incrementAndGet);
        protection.forgot(request, "person@test.local", lookups::incrementAndGet);
        protection.forgot(request, "person@test.local", lookups::incrementAndGet);
        assertThat(lookups.get()).isEqualTo(1);
        assertThatThrownBy(() -> protection.forgot(request, "PERSON@test.local", lookups::incrementAndGet))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void limitsIpAcrossDistinctEmailsAndPadsBothLookupAndCooldownPaths() {
        AccountSecurityProperties properties = properties();
        properties.setForgotIpRequests(2);
        properties.setMinimumResponseMillis(10);
        PasswordRequestProtection protection = protection(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.2");
        for (int i = 0; i < 2; i++) {
            long started = System.nanoTime();
            protection.forgot(request, "unknown@test.local", () -> {});
            assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(10_000_000);
        }
        assertThatThrownBy(() -> protection.forgot(request, "another@test.local", () -> {}))
                .isInstanceOf(RateLimitExceededException.class);
    }

    private AccountSecurityProperties properties() {
        var properties = new AccountSecurityProperties();
        properties.setEnabled(true);
        properties.setMinimumResponseMillis(0);
        return properties;
    }

    private PasswordRequestProtection protection(AccountSecurityProperties properties) {
        var redis = new RedisEphemeralProperties();
        redis.setKeyHashSecret(TestSecrets.newBase64Key());
        return new PasswordRequestProtection(properties, new InMemoryAuthRateLimitStore(),
                new SecurityKeyHasher(redis), new ClientIpResolver(new AuthRateLimitConfig()));
    }
}
