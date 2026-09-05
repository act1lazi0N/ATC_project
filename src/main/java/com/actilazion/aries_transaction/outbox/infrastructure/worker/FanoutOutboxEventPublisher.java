package com.actilazion.aries_transaction.outbox.infrastructure.worker;

import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.outbox", name = "publisher", havingValue = "fanout")
public class FanoutOutboxEventPublisher implements OutboxEventPublisher {
    private final List<OutboxEventSink> sinks;

    @Override
    public boolean publish(OutboxEvent event) {
        List<OutboxEventSink> applicable = sinks.stream()
                .filter(sink -> sink.supports(event))
                .toList();
        if (applicable.isEmpty()) {
            log.error("[OUTBOX] No enabled sink supports event id={} type={}", event.getId(), event.getEventType());
            return false;
        }
        for (OutboxEventSink sink : applicable) {
            sink.accept(event);
            log.info("[OUTBOX] Sink durably accepted event id={} type={} sink={}",
                    event.getId(), event.getEventType(), sink.name());
        }
        return true;
    }
}
