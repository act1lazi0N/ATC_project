package com.actilazion.aries_transaction.notification.infrastructure;

import com.actilazion.aries_transaction.notification.domain.EmailDelivery;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, UUID> {
    @Modifying
    @Query(value = """
            UPDATE email_deliveries d SET status = 'CANCELLED', next_attempt_at = NULL,
                claim_token = NULL, last_error_code = 'PASSWORD_RESET_UNUSABLE'
            FROM password_reset_challenges c, users u
            WHERE d.password_reset_challenge_id = c.id AND c.user_id = u.id
              AND d.status IN ('PENDING', 'FAILED', 'DEAD_LETTERED')
              AND (c.expires_at <= :now OR c.consumed_at IS NOT NULL OR c.invalidated_at IS NOT NULL
                   OR NOT u.is_active OR c.email <> u.email)
            """, nativeQuery = true)
    int cancelUnusablePasswordResets(@Param("now") OffsetDateTime now);

    @Modifying
    @Query(value = """
            DELETE FROM email_deliveries d
            WHERE d.status IN ('DELIVERED', 'CANCELLED') AND d.updated_at < :cutoff
              AND (d.purpose = 'PASSWORD_CHANGED' OR
                (d.purpose = 'PASSWORD_RESET' AND EXISTS (
                  SELECT 1 FROM password_reset_challenges c WHERE c.id = d.password_reset_challenge_id
                    AND GREATEST(c.expires_at, c.consumed_at, c.invalidated_at) < :cutoff)))
            """, nativeQuery = true)
    int deleteResolvedSecurityEmailsBefore(@Param("cutoff") OffsetDateTime cutoff);

    @Query("""
            SELECT delivery.id
            FROM EmailDelivery delivery
            WHERE delivery.status IN :statuses
              AND (delivery.nextAttemptAt IS NULL OR delivery.nextAttemptAt <= :now)
            ORDER BY delivery.createdAt, delivery.id
            """)
    List<UUID> findPublishableIds(
            @Param("statuses") Collection<EmailDeliveryStatus> statuses,
            @Param("now") OffsetDateTime now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT delivery FROM EmailDelivery delivery WHERE delivery.id = :id")
    Optional<EmailDelivery> findByIdForUpdate(@Param("id") UUID id);

    Page<EmailDelivery> findAllByStatus(EmailDeliveryStatus status, Pageable pageable);

    @Modifying
    @Query("""
            UPDATE EmailDelivery delivery
            SET delivery.status = com.actilazion.aries_transaction.notification.domain.EmailDeliveryStatus.CANCELLED,
                delivery.nextAttemptAt = NULL,
                delivery.claimToken = NULL,
                delivery.lastErrorCode = 'PREFERENCE_DISABLED'
            WHERE delivery.notification.recipient.id = :recipientId
              AND delivery.purpose = :purpose
              AND delivery.status IN :statuses
            """)
    int cancelPendingForRecipient(
            @Param("recipientId") UUID recipientId,
            @Param("purpose") com.actilazion.aries_transaction.notification.domain.EmailDeliveryPurpose purpose,
            @Param("statuses") Collection<EmailDeliveryStatus> statuses
    );
}
