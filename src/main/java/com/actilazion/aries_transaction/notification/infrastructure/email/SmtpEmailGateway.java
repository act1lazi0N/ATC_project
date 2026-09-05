package com.actilazion.aries_transaction.notification.infrastructure.email;

import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.notification.email", name = "mode", havingValue = "smtp")
public class SmtpEmailGateway implements EmailGateway {
    private final JavaMailSender mailSender;

    @Override
    public void send(EmailMessage message) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setTo(message.to());
            helper.setFrom(message.from());
            helper.setSubject(message.subject());
            helper.setText(message.textBody(), message.htmlBody());
            mimeMessage.setHeader("Message-ID", "<" + message.messageId() + "@aries.local>");
            mailSender.send(mimeMessage);
        } catch (MailAuthenticationException ex) {
            throw new EmailGatewayException(false, "SMTP_AUTHENTICATION_FAILED", ex);
        } catch (MailException ex) {
            if (hasCause(ex, SendFailedException.class)) {
                throw new EmailGatewayException(false, "SMTP_RECIPIENT_REJECTED", ex);
            }
            throw new EmailGatewayException(true, "SMTP_TEMPORARY_FAILURE", ex);
        } catch (Exception ex) {
            throw new EmailGatewayException(true, "SMTP_MESSAGE_FAILURE", ex);
        }
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
