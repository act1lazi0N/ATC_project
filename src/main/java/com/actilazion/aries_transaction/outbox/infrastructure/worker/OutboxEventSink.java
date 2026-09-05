package com.actilazion.aries_transaction.outbox.infrastructure.worker;

import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;

public interface OutboxEventSink {
    String name();

    boolean supports(OutboxEvent event);

    void accept(OutboxEvent event);
}
