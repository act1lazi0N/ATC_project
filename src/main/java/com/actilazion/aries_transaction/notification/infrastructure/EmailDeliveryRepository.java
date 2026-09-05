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
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT delivery
            FROM EmailDelivery delivery
            LEFT JOIN FETCH delivery.notification notification
            LEFT JOIN FETCH notification.recipient
            LEFT JOIN FETCH delivery.verificationChallenge challenge
            LEFT JOIN FETCH challenge.user
            WHERE delivery.status IN :statuses
              AND (delivery.nextAttemptAt IS NULL OR delivery.nextAttemptAt <= :now)
            ORDER BY delivery.createdAt, delivery.id
            """)
    List<EmailDelivery> findPublishableWithLock(
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
