package com.actilazion.aries_transaction.webhook.infrastructure;

import com.actilazion.aries_transaction.webhook.domain.WebhookDeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WebhookDeliveryAttemptRepository extends JpaRepository<WebhookDeliveryAttempt, UUID> {
}
