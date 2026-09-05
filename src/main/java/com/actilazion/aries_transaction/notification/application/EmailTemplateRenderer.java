package com.actilazion.aries_transaction.notification.application;

import com.actilazion.aries_transaction.identity.application.EmailVerificationTokenService;
import com.actilazion.aries_transaction.identity.domain.EmailVerificationChallenge;
import com.actilazion.aries_transaction.notification.domain.EmailDelivery;
import com.actilazion.aries_transaction.notification.domain.Notification;
import com.actilazion.aries_transaction.notification.infrastructure.email.EmailMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class EmailTemplateRenderer {
    private final NotificationProperties properties;
    private final EmailVerificationTokenService tokenService;

    public EmailTemplateRenderer(NotificationProperties properties, EmailVerificationTokenService tokenService) {
        this.properties = properties;
        this.tokenService = tokenService;
    }

    public EmailMessage render(EmailDelivery delivery) {
        return switch (delivery.getPurpose()) {
            case EMAIL_VERIFICATION -> verification(delivery, delivery.getVerificationChallenge());
            case TRANSACTION_NOTIFICATION, WEBHOOK_ALERT -> notification(delivery, delivery.getNotification());
        };
    }

    private EmailMessage verification(EmailDelivery delivery, EmailVerificationChallenge challenge) {
        String token = tokenService.tokenFor(challenge);
        String separator = properties.getEmail().getPublicBaseUrl().contains("?") ? "&" : "?";
        String url = properties.getEmail().getPublicBaseUrl() + separator + "token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        String text = "Verify your Aries email address: " + url
                + "\nThis link expires at " + challenge.getExpiresAt() + ".";
        String html = "<p>Verify your Aries email address:</p><p><a href=\""
                + HtmlUtils.htmlEscape(url) + "\">Verify email</a></p><p>This link expires at "
                + HtmlUtils.htmlEscape(challenge.getExpiresAt().toString()) + ".</p>";
        return message(delivery, challenge.getUser().getEmail(), "Verify your Aries email", text, html);
    }

    private EmailMessage notification(EmailDelivery delivery, Notification notification) {
        String text = notification.getMessage() + "\nReference: " + notification.getId();
        String html = "<h1>" + HtmlUtils.htmlEscape(notification.getTitle()) + "</h1><p>"
                + HtmlUtils.htmlEscape(notification.getMessage()) + "</p><p>Reference: "
                + notification.getId() + "</p>";
        return message(delivery, notification.getRecipient().getEmail(), notification.getTitle(), text, html);
    }

    private EmailMessage message(
            EmailDelivery delivery,
            String to,
            String subject,
            String text,
            String html
    ) {
        return new EmailMessage(
                to,
                properties.getEmail().getFrom(),
                subject,
                text,
                html,
                delivery.getId().toString()
        );
    }
}
