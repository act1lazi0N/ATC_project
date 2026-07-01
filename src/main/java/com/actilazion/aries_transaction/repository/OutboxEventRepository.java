package com.actilazion.aries_transaction.repository;

import com.actilazion.aries_transaction.entity.OutboxEvent;
import com.actilazion.aries_transaction.entity.enums.OutboxEventStatus;
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
