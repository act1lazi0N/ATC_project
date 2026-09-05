package com.actilazion.aries_transaction.notification.infrastructure.email;

import com.actilazion.aries_transaction.identity.application.EmailVerificationTokenService;
import com.actilazion.aries_transaction.notification.application.NotificationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;

@Component
@ConditionalOnProperty(prefix = "app.notification.email", name = "worker-enabled", havingValue = "true")
public class NotificationEmailConfigurationValidator implements SmartLifecycle {
    private final NotificationProperties properties;
    private final EmailVerificationTokenService tokenService;
    private final Environment environment;
    private volatile boolean running;

    public NotificationEmailConfigurationValidator(
            NotificationProperties properties,
            EmailVerificationTokenService tokenService,
            Environment environment
    ) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.environment = environment;
    }

    @Override
    public void start() {
        var email = properties.getEmail();
        if (!"smtp".equalsIgnoreCase(email.getMode())) {
            throw new IllegalStateException("Notification email worker requires NOTIFICATION_EMAIL_MODE=smtp");
        }
        requireText(email.getFrom(), "NOTIFICATION_EMAIL_FROM");
        requireText(email.getPublicBaseUrl(), "NOTIFICATION_PUBLIC_BASE_URL");
        requireText(environment.getProperty("spring.mail.host"), "SPRING_MAIL_HOST");
        tokenService.requireConfigured();
        URI publicUrl = URI.create(email.getPublicBaseUrl());
        if (publicUrl.getHost() == null) {
            throw new IllegalStateException("NOTIFICATION_PUBLIC_BASE_URL must contain a valid host");
        }
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (production && !"https".equalsIgnoreCase(publicUrl.getScheme())) {
            throw new IllegalStateException("Production notification public URL must use HTTPS");
        }
        if (production && !environment.getProperty(
                "spring.mail.properties.mail.smtp.starttls.enable", Boolean.class, false)) {
            throw new IllegalStateException("Production SMTP must enable STARTTLS");
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when notification email worker is enabled");
        }
    }
}
