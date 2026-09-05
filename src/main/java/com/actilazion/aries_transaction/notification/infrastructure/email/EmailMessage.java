package com.actilazion.aries_transaction.notification.infrastructure.email;

public record EmailMessage(
        String to,
        String from,
        String subject,
        String textBody,
        String htmlBody,
        String messageId
) {
}
