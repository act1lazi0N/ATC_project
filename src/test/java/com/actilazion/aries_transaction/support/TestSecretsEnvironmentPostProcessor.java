package com.actilazion.aries_transaction.support;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

import java.util.Map;

/** Supplies independent, stable keys per test environment after profile configuration is loaded. */
public final class TestSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String PROPERTY_SOURCE = "generatedTestSecrets";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("test"))
                || environment.getPropertySources().contains(PROPERTY_SOURCE)) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, Map.of(
                "jwt.secret", TestSecrets.newBase64Key(),
                "security.ephemeral.key-hash-secret", TestSecrets.newBase64Key(),
                "app.notification.email.verification-signing-key", TestSecrets.newBase64Key()
        )));
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
