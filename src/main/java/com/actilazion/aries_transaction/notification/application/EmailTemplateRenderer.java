package com.actilazion.aries_transaction.notification.application;

import com.actilazion.aries_transaction.identity.application.EmailVerificationTokenService;
import com.actilazion.aries_transaction.identity.application.PasswordResetTokenService;
import com.actilazion.aries_transaction.identity.application.AccountSecurityProperties;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
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
    private final PasswordResetTokenService resetTokens;
    private final AccountSecurityProperties accountSecurity;
    private final UserRepository users;

    public EmailTemplateRenderer(NotificationProperties properties, EmailVerificationTokenService tokenService,
                                 PasswordResetTokenService resetTokens, AccountSecurityProperties accountSecurity,
                                 UserRepository users) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.resetTokens = resetTokens;
        this.accountSecurity = accountSecurity;
        this.users = users;
    }

    public EmailMessage render(EmailDelivery delivery) {
        return switch (delivery.getPurpose()) {
            case EMAIL_VERIFICATION -> verification(delivery, delivery.getVerificationChallenge());
            case PASSWORD_RESET -> passwordReset(delivery);
            case PASSWORD_CHANGED -> passwordChanged(delivery);
            case TRANSACTION_NOTIFICATION, WEBHOOK_ALERT -> notification(delivery, delivery.getNotification());
        };
    }

    private EmailMessage passwordReset(EmailDelivery delivery) {
        var challenge = delivery.getPasswordResetChallenge();
        String url = accountSecurity.getResetPublicUrl() + "?token="
                + URLEncoder.encode(resetTokens.tokenFor(challenge), StandardCharsets.UTF_8);
        String text = "Reset your Aries password: " + url + "\nThis link expires at "
                + challenge.getExpiresAt() + ". If you did not request this, ignore this email.";
        String html = "<p>Reset your Aries password:</p><p><a href=\"" + HtmlUtils.htmlEscape(url)
                + "\">Reset password</a></p><p>This link expires at "
                + HtmlUtils.htmlEscape(challenge.getExpiresAt().toString())
                + ". If you did not request this, ignore this email.</p>";
        return message(delivery, challenge.getEmail(), "Reset your Aries password", text, html);
    }

    private EmailMessage passwordChanged(EmailDelivery delivery) {
        var event = delivery.getSecurityAuditEvent();
        var recipient = users.findById(event.getUserId()).orElseThrow();
        String text = "Your Aries password was changed at " + event.getCreatedAt()
                + ". All existing sessions were revoked. If this was not you, recover your account and contact support.";
        return message(delivery, recipient.getEmail(), "Your Aries password was changed", text,
                "<p>" + HtmlUtils.htmlEscape(text) + "</p>");
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
