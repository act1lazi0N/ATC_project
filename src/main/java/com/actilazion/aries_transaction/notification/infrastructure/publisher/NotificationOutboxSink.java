package com.actilazion.aries_transaction.notification.infrastructure.publisher;

import com.actilazion.aries_transaction.notification.application.NotificationFanoutResult;
import com.actilazion.aries_transaction.notification.application.NotificationFanoutService;
import com.actilazion.aries_transaction.notification.application.NotificationMetrics;
import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.infrastructure.worker.OutboxEventSink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.notification", name = "fanout-enabled", havingValue = "true")
public class NotificationOutboxSink implements OutboxEventSink {
    private static final Set<String> SUPPORTED = Set.of(
            "TransferCompleted", "ReversalCompleted", "RefundCompleted");

    private final NotificationFanoutService fanoutService;
    private final NotificationMetrics metrics;

    @Override
    public String name() {
        return "notification";
    }

    @Override
    public boolean supports(OutboxEvent event) {
        return SUPPORTED.contains(event.getEventType());
    }

    @Override
    public void accept(OutboxEvent event) {
        NotificationFanoutResult result = fanoutService.fanOut(event.getId());
        metrics.fanoutAccepted(result.createdNotificationCount());
        log.info("[NOTIFICATION] Durable fan-out eventId={} eligibleRecipients={} createdNotifications={}",
                event.getId(), result.eligibleRecipientCount(), result.createdNotificationCount());
    }
}
