package com.actilazion.aries_transaction.outbox.infrastructure.worker;

import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;

public interface OutboxEventPublisher {
    /**
     * @return true only when the configured downstream sink has durably accepted the event.
     */
    boolean publish(OutboxEvent event);
}
