package com.actilazion.aries_transaction.outbox.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.outbox.domain.OutboxEventStatus;
import com.actilazion.aries_transaction.outbox.domain.OutboxEventType;
import com.actilazion.aries_transaction.outbox.infrastructure.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxEventService {
    private static final int MAX_ERROR_LENGTH = 500;
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);
    private static final Duration PROCESSING_LEASE_DURATION = Duration.ofMinutes(5);

    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public void recordTransferCompleted(Transaction tx) {
        recordTransactionCompleted(tx, OutboxEventType.TRANSFER_COMPLETED);
    }

    @Transactional
    public void recordReversalCompleted(Transaction tx) {
        recordTransactionCompleted(tx, OutboxEventType.REVERSAL_COMPLETED);
    }

    @Transactional
    public void recordRefundCompleted(Transaction tx) {
        recordTransactionCompleted(tx, OutboxEventType.REFUND_COMPLETED);
    }

    private void recordTransactionCompleted(Transaction tx, OutboxEventType eventType) {
        boolean alreadyRecorded = outboxEventRepository
                .findByAggregateTypeAndAggregateIdAndEventType(
                        eventType.aggregateType(),
                        tx.getId(),
                        eventType.eventType()
                )
                .isPresent();
        if (alreadyRecorded) {
            return;
        }

        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(eventType.aggregateType())
                .aggregateId(tx.getId())
                .eventType(eventType.eventType())
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
    public List<OutboxEvent> claimPublishableEvents(int limit) {
        OffsetDateTime now = OffsetDateTime.now();
        List<OutboxEvent> events = outboxEventRepository.findPublishableEventsWithLock(
                List.of(OutboxEventStatus.PENDING, OutboxEventStatus.FAILED, OutboxEventStatus.PROCESSING),
                now,
                PageRequest.of(0, limit)
        );
        events.forEach(event -> {
            event.setStatus(OutboxEventStatus.PROCESSING);
            event.setLastError(null);
            event.setNextAttemptAt(now.plus(PROCESSING_LEASE_DURATION));
        });
        return outboxEventRepository.saveAllAndFlush(events);
    }

    @Transactional
    public void markPublished(UUID eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + eventId));
        event.setStatus(OutboxEventStatus.PUBLISHED);
        event.setPublishedAt(OffsetDateTime.now());
        event.setLastError(null);
        event.setNextAttemptAt(null);
        outboxEventRepository.save(event);
    }

    @Transactional
    public void markFailed(UUID eventId) {
        markFailed(eventId, "Publisher did not confirm delivery");
    }

    @Transactional
    public void markFailed(UUID eventId, String errorMessage) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + eventId));
        int nextAttempt = event.getAttemptCount() + 1;
        event.setAttemptCount(nextAttempt);
        event.setStatus(OutboxEventStatus.FAILED);
        event.setLastError(truncate(errorMessage));
        event.setNextAttemptAt(OffsetDateTime.now().plus(backoffFor(nextAttempt)));
        outboxEventRepository.save(event);
    }

    private Duration backoffFor(int attemptCount) {
        long seconds = Math.min(MAX_RETRY_DELAY.toSeconds(), 1L << Math.min(attemptCount, 8));
        return Duration.ofSeconds(seconds);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
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
        payload.put("originalTransactionId", tx.getOriginalTransaction() != null ? tx.getOriginalTransaction().getId().toString() : null);
        payload.put("refundedAmount", tx.getRefundedAmount() != null ? tx.getRefundedAmount().toPlainString() : null);
        payload.put("createdAt", tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null);
        payload.put("completedAt", tx.getCompletedAt() != null ? tx.getCompletedAt().toString() : null);

        return payload;
    }
}
