package com.actilazion.aries_transaction.notification;

import com.actilazion.aries_transaction.identity.application.EmailVerificationTokenService;
import com.actilazion.aries_transaction.notification.application.NotificationProperties;
import com.actilazion.aries_transaction.notification.infrastructure.email.NotificationEmailConfigurationValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NotificationEmailConfigurationValidatorTest {
    @Test
    void productionRejectsEachMissingTransportProtection() {
        for (String property : new String[]{"starttls.enable", "starttls.required", "ssl.checkserveridentity"}) {
            MockEnvironment environment = secureEnvironment();
            environment.setActiveProfiles("prod");
            environment.setProperty("spring.mail.properties.mail.smtp." + property, "false");
            assertThatThrownBy(() -> validator(environment, "https://aries.test/verify-email").start())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void productionAcceptsSecureTransportAndStillRejectsHttpVerificationLinks() {
        MockEnvironment environment = secureEnvironment();
        environment.setActiveProfiles("prod");
        var validator = validator(environment, "https://aries.test/verify-email");
        validator.start();
        assertThat(validator.isRunning()).isTrue();
        assertThatThrownBy(() -> validator(environment, "http://aries.test/verify-email").start())
                .hasMessageContaining("HTTPS");
    }

    @Test
    void developmentAllowsPlaintextMailpit() {
        var validator = validator(new MockEnvironment().withProperty("spring.mail.host", "localhost"),
                "http://localhost:3000/verify-email");
        validator.start();
        assertThat(validator.isRunning()).isTrue();
    }

    @Test
    void authenticatedSmtpRequiresBothCredentialsWithoutExposingTheirValues() {
        var environment = secureEnvironment().withProperty("spring.mail.properties.mail.smtp.auth", "true");
        assertThatThrownBy(() -> validator(environment, "https://aries.test/verify-email").start())
                .hasMessage("SPRING_MAIL_USERNAME is required when notification email worker is enabled");
        environment.setProperty("spring.mail.username", "test-sender@example.test");
        assertThatThrownBy(() -> validator(environment, "https://aries.test/verify-email").start())
                .hasMessage("SPRING_MAIL_PASSWORD is required when notification email worker is enabled");
        environment.setProperty("spring.mail.password", "test-only-password");
        var validator = validator(environment, "https://aries.test/verify-email");
        validator.start();
        assertThat(validator.isRunning()).isTrue();
    }

    private MockEnvironment secureEnvironment() {
        return new MockEnvironment().withProperty("spring.mail.host", "smtp.test")
                .withProperty("spring.mail.properties.mail.smtp.starttls.enable", "true")
                .withProperty("spring.mail.properties.mail.smtp.starttls.required", "true")
                .withProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity", "true");
    }

    private NotificationEmailConfigurationValidator validator(MockEnvironment environment, String url) {
        var properties = new NotificationProperties();
        properties.getEmail().setMode("smtp");
        properties.getEmail().setFrom("no-reply@aries.test");
        properties.getEmail().setPublicBaseUrl(url);
        return new NotificationEmailConfigurationValidator(properties,
                mock(EmailVerificationTokenService.class), environment);
    }
}
