package com.actilazion.aries_transaction.notification.application;

import com.actilazion.aries_transaction.account.application.AccountPartyMasking;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.notification.domain.NotificationDirection;
import com.actilazion.aries_transaction.notification.domain.NotificationType;
import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionOperation;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class NotificationTransactionProjector {

    public NotificationTransactionProjection project(
            OutboxEvent event,
            Transaction transaction,
            UUID recipientId
    ) {
        assertMatchingAggregate(event, transaction);
        NotificationType type = typeFor(event.getEventType());
        assertMatchingOperation(type, transaction.getOperation());
        NotificationDirection direction = direction(transaction, recipientId);
        String amount = transaction.getAmount().toPlainString();
        String currency = transaction.getCurrency();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", transaction.getId().toString());
        payload.put("originalTransactionId", transaction.getOriginalTransaction() == null
                ? null
                : transaction.getOriginalTransaction().getId().toString());
        payload.put("operation", transaction.getOperation().name());
        payload.put("amount", amount);
        payload.put("currency", currency);
        payload.put("direction", direction.name());
        payload.put("fromAccountDisplay", AccountPartyMasking.maskedNumber(transaction.getFromAccount()));
        payload.put("toAccountDisplay", AccountPartyMasking.maskedNumber(transaction.getToAccount()));
        OffsetDateTime occurredAt = transaction.getCompletedAt() != null
                ? transaction.getCompletedAt()
                : event.getCreatedAt();
        payload.put("occurredAt", occurredAt.toString());

        return new NotificationTransactionProjection(
                type,
                title(type),
                message(type, direction, amount, currency, transaction),
                Collections.unmodifiableMap(payload),
                occurredAt
        );
    }

    private void assertMatchingAggregate(OutboxEvent event, Transaction transaction) {
        if (!"Transaction".equals(event.getAggregateType())
                || event.getAggregateId() == null
                || !event.getAggregateId().equals(transaction.getId())) {
            throw new IllegalArgumentException("Notification event does not match its transaction aggregate");
        }
    }

    private NotificationType typeFor(String eventType) {
        return switch (eventType) {
            case "TransferCompleted" -> NotificationType.TRANSFER_COMPLETED;
            case "ReversalCompleted" -> NotificationType.REVERSAL_COMPLETED;
            case "RefundCompleted" -> NotificationType.REFUND_COMPLETED;
            default -> throw new IllegalArgumentException("Unsupported notification outbox event type: " + eventType);
        };
    }

    private void assertMatchingOperation(NotificationType type, TransactionOperation operation) {
        TransactionOperation expected = switch (type) {
            case TRANSFER_COMPLETED -> TransactionOperation.TRANSFER;
            case REVERSAL_COMPLETED -> TransactionOperation.REVERSAL;
            case REFUND_COMPLETED -> TransactionOperation.REFUND;
            default -> throw new IllegalArgumentException("Not a transaction notification type: " + type);
        };
        if (operation != expected) {
            throw new IllegalArgumentException("Notification event type does not match transaction operation");
        }
    }

    private NotificationDirection direction(Transaction transaction, UUID recipientId) {
        boolean ownsFrom = ownedBy(transaction.getFromAccount(), recipientId);
        boolean ownsTo = ownedBy(transaction.getToAccount(), recipientId);
        if (ownsFrom && ownsTo) {
            return NotificationDirection.OWN_ACCOUNTS;
        }
        if (ownsFrom) {
            return NotificationDirection.OUTGOING;
        }
        if (ownsTo) {
            return NotificationDirection.INCOMING;
        }
        throw new IllegalArgumentException("Notification recipient is not a transaction party");
    }

    private boolean ownedBy(Account account, UUID userId) {
        return account != null && account.getUser() != null && userId.equals(account.getUser().getId());
    }

    private String title(NotificationType type) {
        return switch (type) {
            case TRANSFER_COMPLETED -> "Transfer completed";
            case REVERSAL_COMPLETED -> "Reversal completed";
            case REFUND_COMPLETED -> "Refund completed";
            default -> throw new IllegalArgumentException("Not a transaction notification type: " + type);
        };
    }

    private String message(
            NotificationType type,
            NotificationDirection direction,
            String amount,
            String currency,
            Transaction transaction
    ) {
        String action = switch (direction) {
            case INCOMING -> "received";
            case OUTGOING -> "sent";
            case OWN_ACCOUNTS -> "moved";
        };
        String account = switch (direction) {
            case INCOMING -> AccountPartyMasking.maskedNumber(transaction.getToAccount());
            case OUTGOING -> AccountPartyMasking.maskedNumber(transaction.getFromAccount());
            case OWN_ACCOUNTS -> AccountPartyMasking.maskedNumber(transaction.getToAccount());
        };
        String subject = switch (type) {
            case TRANSFER_COMPLETED -> "Transfer";
            case REVERSAL_COMPLETED -> "Reversal";
            case REFUND_COMPLETED -> "Refund";
            default -> throw new IllegalArgumentException("Not a transaction notification type: " + type);
        };
        return subject + " completed: you " + action + " " + amount + " " + currency
                + " using account " + account + ".";
    }
}
