package com.actilazion.aries_transaction.notification.application;

import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.notification.domain.NotificationSourceKind;
import com.actilazion.aries_transaction.notification.infrastructure.NotificationRepository;
import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.infrastructure.OutboxEventRepository;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationFanoutService {
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationTransactionProjector projector;
    private final NotificationCreator notificationCreator;

    @Transactional
    public NotificationFanoutResult fanOut(UUID outboxEventId) {
        OutboxEvent event = outboxEventRepository.findByIdWithLock(outboxEventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + outboxEventId));
        Transaction transaction = transactionRepository.findWebhookAggregateById(event.getAggregateId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction aggregate not found: " + event.getAggregateId()));

        Map<UUID, User> recipients = recipients(transaction);
        Set<UUID> existing = notificationRepository.findRecipientIdsByOutboxEventId(event.getId());
        int created = 0;
        for (User recipient : recipients.values()) {
            if (existing.contains(recipient.getId())) {
                continue;
            }
            NotificationTransactionProjection projection = projector.project(event, transaction, recipient.getId());
            if (notificationCreator.create(
                    recipient,
                    NotificationSourceKind.OUTBOX_EVENT,
                    event.getId(),
                    0,
                    projection.type(),
                    projection.title(),
                    projection.message(),
                    projection.payload(),
                    projection.occurredAt())) {
                created++;
            }
        }
        return new NotificationFanoutResult(recipients.size(), created);
    }

    private Map<UUID, User> recipients(Transaction transaction) {
        Map<UUID, User> recipients = new LinkedHashMap<>();
        User from = transaction.getFromAccount().getUser();
        User to = transaction.getToAccount().getUser();
        recipients.put(from.getId(), from);
        recipients.put(to.getId(), to);
        return recipients;
    }
}
