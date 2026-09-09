package com.actilazion.aries_transaction.support;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSecretsEnvironmentPostProcessorTest {
    private final TestSecretsEnvironmentPostProcessor processor = new TestSecretsEnvironmentPostProcessor();
    private final SpringApplication application = new SpringApplication();
    private final List<String> keyProperties = List.of("jwt.secret", "security.ephemeral.key-hash-secret",
            "app.notification.email.verification-signing-key");

    @Test
    void keysAreIndependentStableAndIsolatedBetweenTestEnvironments() {
        MockEnvironment first = new MockEnvironment();
        first.setActiveProfiles("test");
        MockEnvironment second = new MockEnvironment();
        second.setActiveProfiles("test");

        processor.postProcessEnvironment(first, application);
        processor.postProcessEnvironment(second, application);
        List<String> originalKeys = keyProperties.stream().map(first::getRequiredProperty).toList();
        assertThat(originalKeys).doesNotHaveDuplicates();
        for (String property : keyProperties) {
            assertThat(Base64.getDecoder().decode(first.getRequiredProperty(property))).hasSize(32);
            assertThat(first.getRequiredProperty(property)).isNotEqualTo(second.getRequiredProperty(property));
        }

        processor.postProcessEnvironment(first, application);
        assertThat(keyProperties.stream().map(first::getRequiredProperty).toList()).isEqualTo(originalKeys);
    }

    @Test
    void doesNotSupplyFallbackKeysOutsideTestProfile() {
        for (String profile : List.of("default", "dev", "docker", "prod")) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profile);

            processor.postProcessEnvironment(environment, application);

            keyProperties.forEach(property -> assertThat(environment.containsProperty(property)).isFalse());
        }
    }
}
