package com.actilazion.aries_transaction.notification.infrastructure;

import com.actilazion.aries_transaction.notification.domain.EmailDeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface EmailDeliveryAttemptRepository extends JpaRepository<EmailDeliveryAttempt, UUID> {
    List<EmailDeliveryAttempt> findAllByDelivery_IdOrderByAttemptNumber(UUID deliveryId);

    @Modifying
    @Query(value = """
            DELETE FROM email_delivery_attempts
            WHERE created_at < :cutoff
              AND delivery_id IN (
                  SELECT id
                  FROM email_deliveries
                  WHERE status IN ('DELIVERED', 'DEAD_LETTERED', 'CANCELLED')
              )
            """, nativeQuery = true)
    int deleteTerminalBefore(@Param("cutoff") OffsetDateTime cutoff);
}
