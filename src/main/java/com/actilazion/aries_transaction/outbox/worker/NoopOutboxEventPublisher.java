package com.actilazion.aries_transaction.outbox.worker;

import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "publisher", havingValue = "noop", matchIfMissing = true)
public class NoopOutboxEventPublisher implements OutboxEventPublisher {
    @Override
    public boolean publish(OutboxEvent event) {
        log.info(
                "[OUTBOX] Event ready for delivery id={} type={} aggregateId={} publisher=noop",
                event.getId(),
                event.getEventType(),
                event.getAggregateId()
        );
        return false;
    }
}
