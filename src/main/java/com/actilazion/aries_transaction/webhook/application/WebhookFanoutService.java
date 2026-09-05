package com.actilazion.aries_transaction.webhook.application;

import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.infrastructure.OutboxEventRepository;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import com.actilazion.aries_transaction.webhook.domain.WebhookDelivery;
import com.actilazion.aries_transaction.webhook.domain.WebhookDeliveryStatus;
import com.actilazion.aries_transaction.webhook.domain.WebhookEndpoint;
import com.actilazion.aries_transaction.webhook.domain.WebhookEndpointState;
import com.actilazion.aries_transaction.webhook.domain.WebhookEventType;
import com.actilazion.aries_transaction.webhook.infrastructure.WebhookDeliveryRepository;
import com.actilazion.aries_transaction.webhook.infrastructure.WebhookEndpointSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebhookFanoutService {
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionRepository transactionRepository;
    private final WebhookEndpointSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookMerchantAudienceResolver audienceResolver;
    private final WebhookEventPayloadFactory payloadFactory;

    @Transactional
    public WebhookFanoutResult fanOut(UUID outboxEventId) {
        OutboxEvent event = outboxEventRepository.findByIdWithLock(outboxEventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + outboxEventId));
        WebhookEventType eventType = WebhookEventType.fromOutboxEventType(event.getEventType())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported webhook outbox event type: " + event.getEventType()));
        Transaction transaction = transactionRepository.findWebhookAggregateById(event.getAggregateId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction aggregate not found: " + event.getAggregateId()));

        Set<UUID> merchantIds = audienceResolver.resolve(transaction);
        if (merchantIds.isEmpty()) {
            return new WebhookFanoutResult(0, 0);
        }

        List<WebhookEndpoint> endpoints = subscriptionRepository.findEligibleEndpoints(
                merchantIds,
                eventType,
                WebhookEndpointState.ENABLED,
                event.getCreatedAt()
        );
        Set<UUID> existingEndpointIds = deliveryRepository.findEndpointIdsByOutboxEventId(event.getId());
        List<WebhookDelivery> newDeliveries = new ArrayList<>();
        for (WebhookEndpoint endpoint : endpoints) {
            if (existingEndpointIds.contains(endpoint.getId())) {
                continue;
            }
            UUID deliveryId = UUID.randomUUID();
            newDeliveries.add(WebhookDelivery.builder()
                    .id(deliveryId)
                    .endpoint(endpoint)
                    .outboxEventId(event.getId())
                    .eventType(eventType.publicType())
                    .payload(payloadFactory.create(
                            event,
                            transaction,
                            endpoint.getOwner().getId(),
                            deliveryId,
                            eventType
                    ))
                    .status(WebhookDeliveryStatus.PENDING)
                    .build());
        }
        deliveryRepository.saveAllAndFlush(newDeliveries);
        return new WebhookFanoutResult(endpoints.size(), newDeliveries.size());
    }
}
