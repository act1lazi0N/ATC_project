package com.actilazion.aries_transaction.webhook.infrastructure;

import com.actilazion.aries_transaction.webhook.domain.WebhookEndpoint;
import com.actilazion.aries_transaction.webhook.domain.WebhookEndpointState;
import com.actilazion.aries_transaction.webhook.domain.WebhookEndpointSubscription;
import com.actilazion.aries_transaction.webhook.domain.WebhookEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookEndpointSubscriptionRepository
        extends JpaRepository<WebhookEndpointSubscription, UUID> {

    @Query("""
            SELECT DISTINCT endpoint
            FROM WebhookEndpointSubscription subscription
            JOIN subscription.endpoint endpoint
            JOIN FETCH endpoint.owner owner
            WHERE owner.id IN :merchantIds
              AND owner.role = com.actilazion.aries_transaction.identity.domain.Role.MERCHANT
              AND owner.isActive = true
              AND endpoint.state = :endpointState
              AND endpoint.createdAt <= :occurredAt
              AND subscription.eventType = :eventType
              AND subscription.subscribedAt <= :occurredAt
            ORDER BY endpoint.id
            """)
    List<WebhookEndpoint> findEligibleEndpoints(
            @Param("merchantIds") Collection<UUID> merchantIds,
            @Param("eventType") WebhookEventType eventType,
            @Param("endpointState") WebhookEndpointState endpointState,
            @Param("occurredAt") OffsetDateTime occurredAt
    );
}
