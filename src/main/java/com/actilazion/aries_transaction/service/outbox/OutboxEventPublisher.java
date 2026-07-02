package com.actilazion.aries_transaction.service.outbox;

import com.actilazion.aries_transaction.entity.OutboxEvent;

public interface OutboxEventPublisher {
    /**
     * @return true only when the event is durably delivered to an external consumer.
     */
    boolean publish(OutboxEvent event);
}
