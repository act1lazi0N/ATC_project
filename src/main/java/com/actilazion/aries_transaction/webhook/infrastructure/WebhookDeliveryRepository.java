package com.actilazion.aries_transaction.webhook.infrastructure;

import com.actilazion.aries_transaction.webhook.domain.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {
    List<WebhookDelivery> findAllByOutboxEventIdOrderByEndpoint_Id(UUID outboxEventId);

    @Query("""
            SELECT delivery.endpoint.id
            FROM WebhookDelivery delivery
            WHERE delivery.outboxEventId = :outboxEventId
            """)
    Set<UUID> findEndpointIdsByOutboxEventId(@Param("outboxEventId") UUID outboxEventId);
}
