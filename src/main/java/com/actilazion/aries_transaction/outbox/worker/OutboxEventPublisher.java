package com.actilazion.aries_transaction.outbox.worker;

import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;

public interface OutboxEventPublisher {
    /**
     * @return true only when the event is durably delivered to an external consumer.
     */
    boolean publish(OutboxEvent event);
}
