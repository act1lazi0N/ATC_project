package com.actilazion.aries_transaction.webhook.infrastructure.publisher;

import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.infrastructure.worker.OutboxEventSink;
import com.actilazion.aries_transaction.webhook.application.WebhookFanoutResult;
import com.actilazion.aries_transaction.webhook.application.WebhookFanoutService;
import com.actilazion.aries_transaction.webhook.domain.WebhookEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.webhook", name = "fanout-enabled", havingValue = "true")
public class WebhookOutboxSink implements OutboxEventSink {
    private final WebhookFanoutService fanoutService;

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public boolean supports(OutboxEvent event) {
        return WebhookEventType.fromOutboxEventType(event.getEventType()).isPresent();
    }

    @Override
    public void accept(OutboxEvent event) {
        WebhookFanoutResult result = fanoutService.fanOut(event.getId());
        log.info("[WEBHOOK] Durable fan-out eventId={} eligibleEndpoints={} createdDeliveries={}",
                event.getId(), result.eligibleEndpointCount(), result.createdDeliveryCount());
    }
}
