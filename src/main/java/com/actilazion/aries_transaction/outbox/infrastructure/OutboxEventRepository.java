package com.actilazion.aries_transaction.outbox.infrastructure;

import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.domain.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
