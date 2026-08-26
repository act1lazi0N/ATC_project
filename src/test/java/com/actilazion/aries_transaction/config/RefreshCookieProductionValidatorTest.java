package com.actilazion.aries_transaction.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshCookieProductionValidatorTest {
    @Test
    void validate_prodRejectsLoopbackOrigin() {
        RefreshCookiePolicy policy = new RefreshCookiePolicy();
        policy.setAllowedOrigins("http://localhost:3000");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("docker", "prod");

        assertThatThrownBy(() -> new RefreshCookieProductionValidator(policy, environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-loopback");
    }

    @Test
    void validate_prodAcceptsExplicitRemoteOrigin() {
        RefreshCookiePolicy policy = new RefreshCookiePolicy();
        policy.setAllowedOrigins("https://app.example.test");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("docker", "prod");

        assertThatCode(() -> new RefreshCookieProductionValidator(policy, environment).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void validate_prodRejectsEmptyOriginList() {
        RefreshCookiePolicy policy = new RefreshCookiePolicy();
        policy.setAllowedOrigins(" ");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("docker", "prod");

        assertThatThrownBy(() -> new RefreshCookieProductionValidator(policy, environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("<empty>");
    }
}
