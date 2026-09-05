package com.actilazion.aries_transaction.notification.application;

import com.actilazion.aries_transaction.notification.domain.NotificationSourceKind;
import com.actilazion.aries_transaction.notification.domain.NotificationType;
import com.actilazion.aries_transaction.webhook.domain.WebhookDelivery;
import com.actilazion.aries_transaction.webhook.domain.WebhookEndpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WebhookOperationalNotificationService {
    private final NotificationCreator notificationCreator;

    @Transactional
    public boolean endpointDisabled(WebhookEndpoint endpoint, OffsetDateTime occurredAt) {
        String host = safeHost(endpoint.getCanonicalUrl());
        return notificationCreator.create(
                endpoint.getOwner(),
                NotificationSourceKind.WEBHOOK_ENDPOINT,
                endpoint.getId(),
                endpoint.getVersion(),
                NotificationType.WEBHOOK_ENDPOINT_DISABLED,
                "Webhook endpoint disabled",
                "Webhook endpoint " + endpoint.getName() + " at " + host + " was disabled.",
                Map.of("endpointId", endpoint.getId().toString(), "endpointName", endpoint.getName(), "host", host),
                occurredAt
        );
    }

    @Transactional
    public boolean deliveryDeadLettered(WebhookDelivery delivery, OffsetDateTime occurredAt) {
        WebhookEndpoint endpoint = delivery.getEndpoint();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("endpointId", endpoint.getId().toString());
        payload.put("endpointName", endpoint.getName());
        payload.put("host", safeHost(endpoint.getCanonicalUrl()));
        payload.put("deliveryId", delivery.getId().toString());
        payload.put("eventType", delivery.getEventType());
        payload.put("attemptCount", delivery.getAttemptCount());
        if (delivery.getLastError() != null && !delivery.getLastError().isBlank()) {
            payload.put("errorCode", safeErrorCode(delivery.getLastError()));
        }
        return notificationCreator.create(
                endpoint.getOwner(),
                NotificationSourceKind.WEBHOOK_DELIVERY,
                delivery.getId(),
                delivery.getAttemptCount(),
                NotificationType.WEBHOOK_DELIVERY_DEAD_LETTERED,
                "Webhook delivery needs attention",
                "A webhook delivery for endpoint " + endpoint.getName() + " exhausted its retries.",
                payload,
                occurredAt
        );
    }

    private String safeHost(String canonicalUrl) {
        try {
            String host = URI.create(canonicalUrl).getHost();
            return host == null || host.isBlank() ? "unknown-host" : host;
        } catch (IllegalArgumentException ex) {
            return "unknown-host";
        }
    }

    private String safeErrorCode(String value) {
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9_ -]", "")
                .replace(' ', '_');
        return normalized.substring(0, Math.min(normalized.length(), 100));
    }
}
