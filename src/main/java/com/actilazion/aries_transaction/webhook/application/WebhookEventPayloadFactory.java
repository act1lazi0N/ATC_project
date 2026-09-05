package com.actilazion.aries_transaction.webhook.application;

import com.actilazion.aries_transaction.account.application.AccountPartyMasking;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.webhook.domain.WebhookDirection;
import com.actilazion.aries_transaction.webhook.domain.WebhookEventType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class WebhookEventPayloadFactory {
    private static final int CONTRACT_VERSION = 1;

    public Map<String, Object> create(
            OutboxEvent event,
            Transaction transaction,
            UUID merchantId,
            UUID deliveryId,
            WebhookEventType eventType
    ) {
        assertMatchingAggregate(event, transaction);
        if (transaction.getOperation() != eventType.operation()) {
            throw new IllegalArgumentException("Webhook event type does not match transaction operation");
        }
        Map<String, Object> internalPayload = event.getPayload();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transactionId", transaction.getId().toString());
        data.put("operation", eventType.operation().name());
        data.put("amount", requiredString(internalPayload, "amount"));
        data.put("currency", requiredString(internalPayload, "currency"));
        data.put("status", requiredString(internalPayload, "status"));
        data.put("direction", direction(transaction, merchantId).name());
        data.put("fromParty", party(transaction.getFromAccount(), merchantId));
        data.put("toParty", party(transaction.getToAccount(), merchantId));
        data.put("originalTransactionId", nullableString(internalPayload, "originalTransactionId"));
        data.put("completedAt", requiredString(internalPayload, "completedAt"));

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.getId().toString());
        envelope.put("deliveryId", deliveryId.toString());
        envelope.put("type", eventType.publicType());
        envelope.put("version", CONTRACT_VERSION);
        envelope.put("occurredAt", requiredString(internalPayload, "completedAt"));
        envelope.put("data", Collections.unmodifiableMap(data));
        return Collections.unmodifiableMap(envelope);
    }

    private void assertMatchingAggregate(OutboxEvent event, Transaction transaction) {
        if (!"Transaction".equals(event.getAggregateType())
                || event.getAggregateId() == null
                || !event.getAggregateId().equals(transaction.getId())) {
            throw new IllegalArgumentException("Webhook event does not match its transaction aggregate");
        }
    }

    private WebhookDirection direction(Transaction transaction, UUID merchantId) {
        boolean ownsFrom = ownedBy(transaction.getFromAccount(), merchantId);
        boolean ownsTo = ownedBy(transaction.getToAccount(), merchantId);
        if (ownsFrom && ownsTo) {
            return WebhookDirection.OWN_ACCOUNTS;
        }
        if (ownsFrom) {
            return WebhookDirection.OUTGOING;
        }
        if (ownsTo) {
            return WebhookDirection.INCOMING;
        }
        throw new IllegalArgumentException("Merchant is not a transaction party");
    }

    private Map<String, Object> party(Account account, UUID merchantId) {
        boolean owned = ownedBy(account, merchantId);
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("accountNumberDisplay", owned
                ? account.getAccountNumber()
                : AccountPartyMasking.maskedNumber(account));
        party.put("exposure", owned ? "FULL_OWNED" : "MASKED_COUNTERPARTY");
        party.put("ownedByMerchant", owned);
        return Collections.unmodifiableMap(party);
    }

    private boolean ownedBy(Account account, UUID merchantId) {
        return account != null
                && account.getUser() != null
                && merchantId != null
                && merchantId.equals(account.getUser().getId());
    }

    private String requiredString(Map<String, Object> payload, String field) {
        String value = nullableString(payload, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Outbox payload field is required: " + field);
        }
        return value;
    }

    private String nullableString(Map<String, Object> payload, String field) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get(field);
        return value == null ? null : value.toString();
    }
}
