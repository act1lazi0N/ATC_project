package com.actilazion.aries_transaction.service;

import com.actilazion.aries_transaction.entity.Account;
import com.actilazion.aries_transaction.entity.OutboxEvent;
import com.actilazion.aries_transaction.entity.Transaction;
import com.actilazion.aries_transaction.entity.enums.OutboxEventStatus;
import com.actilazion.aries_transaction.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxEventService {
    public static final String AGGREGATE_TYPE_TRANSACTION = "Transaction";
    public static final String EVENT_TYPE_TRANSFER_COMPLETED = "TransferCompleted";

    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public void recordTransferCompleted(Transaction tx) {
        boolean alreadyRecorded = outboxEventRepository
                .findByAggregateTypeAndAggregateIdAndEventType(
                        AGGREGATE_TYPE_TRANSACTION,
                        tx.getId(),
                        EVENT_TYPE_TRANSFER_COMPLETED
                )
                .isPresent();
        if (alreadyRecorded) {
            return;
        }

        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE_TRANSACTION)
                .aggregateId(tx.getId())
                .eventType(EVENT_TYPE_TRANSFER_COMPLETED)
                .payload(toPayload(tx))
                .status(OutboxEventStatus.PENDING)
                .build();
        outboxEventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> findPendingEvents(int limit) {
        return outboxEventRepository.findAllByStatusOrderByCreatedAtAsc(OutboxEventStatus.PENDING)
                .stream()
                .limit(limit)
                .toList();
    }

    @Transactional
    public void markPublished(UUID eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + eventId));
        event.setStatus(OutboxEventStatus.PUBLISHED);
        event.setPublishedAt(OffsetDateTime.now());
        outboxEventRepository.save(event);
    }

    @Transactional
    public void markFailed(UUID eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + eventId));
        event.setStatus(OutboxEventStatus.FAILED);
        outboxEventRepository.save(event);
    }

    private Map<String, Object> toPayload(Transaction tx) {
        Account fromAccount = tx.getFromAccount();
        Account toAccount = tx.getToAccount();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", tx.getId().toString());
        payload.put("fromAccountId", fromAccount.getId().toString());
        payload.put("toAccountId", toAccount.getId().toString());
        payload.put("userId", tx.getInitiatedBy().getId().toString());
        payload.put("fromUserFullName", fromAccount.getUser().getFullName());
        payload.put("toUserFullName", toAccount.getUser().getFullName());
        payload.put("fromAccountNumber", fromAccount.getAccountNumber());
        payload.put("toAccountNumber", toAccount.getAccountNumber());
        payload.put("amount", tx.getAmount().toPlainString());
        payload.put("currency", tx.getCurrency());
        payload.put("status", tx.getStatus().name());
        payload.put("description", tx.getDescription());
        payload.put("createdAt", tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null);
        payload.put("completedAt", tx.getCompletedAt() != null ? tx.getCompletedAt().toString() : null);

        return payload;
    }
}
