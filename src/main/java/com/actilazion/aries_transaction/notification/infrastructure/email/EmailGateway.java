package com.actilazion.aries_transaction.notification.infrastructure.email;

public interface EmailGateway {
    void send(EmailMessage message);
}
