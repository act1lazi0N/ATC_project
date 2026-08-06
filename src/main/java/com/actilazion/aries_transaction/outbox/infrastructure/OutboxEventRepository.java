package com.actilazion.aries_transaction.outbox.infrastructure;

import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.domain.OutboxEventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    Optional<OutboxEvent> findByAggregateTypeAndAggregateIdAndEventType(
            String aggregateType,
            UUID aggregateId,
            String eventType
    );

    List<OutboxEvent> findAllByStatusOrderByCreatedAtAsc(OutboxEventStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT event FROM OutboxEvent event
    WHERE event.status IN :statuses
      AND (event.nextAttemptAt IS NULL OR event.nextAttemptAt <= :now)
    ORDER BY event.createdAt ASC
    """)
    List<OutboxEvent> findPublishableEventsWithLock(
            @Param("statuses") List<OutboxEventStatus> statuses,
            @Param("now") OffsetDateTime now,
            Pageable pageable
    );
}
