package com.actilazion.aries_transaction.notification.infrastructure;

import com.actilazion.aries_transaction.notification.domain.Notification;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findAllBySourceKindAndSourceIdOrderByRecipient_Id(
            com.actilazion.aries_transaction.notification.domain.NotificationSourceKind sourceKind,
            UUID sourceId
    );

    Page<Notification> findAllByRecipient_Id(UUID recipientId, Pageable pageable);

    Page<Notification> findAllByRecipient_IdAndReadAtIsNull(UUID recipientId, Pageable pageable);

    Page<Notification> findAllByRecipient_IdAndReadAtIsNotNull(UUID recipientId, Pageable pageable);

    long countByRecipient_IdAndReadAtIsNull(UUID recipientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT notification FROM Notification notification WHERE notification.id = :id AND notification.recipient.id = :recipientId")
    Optional<Notification> findOwnedByIdForUpdate(
            @Param("id") UUID id,
            @Param("recipientId") UUID recipientId
    );

    @Modifying
    @Query("""
            UPDATE Notification notification
            SET notification.readAt = :readAt
            WHERE notification.recipient.id = :recipientId
              AND notification.readAt IS NULL
              AND notification.createdAt <= :readThrough
            """)
    int markAllRead(
            @Param("recipientId") UUID recipientId,
            @Param("readThrough") OffsetDateTime readThrough,
            @Param("readAt") OffsetDateTime readAt
    );

    @Query("""
            SELECT notification.recipient.id
            FROM Notification notification
            WHERE notification.sourceKind = com.actilazion.aries_transaction.notification.domain.NotificationSourceKind.OUTBOX_EVENT
              AND notification.sourceId = :outboxEventId
            """)
    Set<UUID> findRecipientIdsByOutboxEventId(@Param("outboxEventId") UUID outboxEventId);

    boolean existsBySourceKindAndSourceIdAndSourceVersionAndRecipient_IdAndType(
            com.actilazion.aries_transaction.notification.domain.NotificationSourceKind sourceKind,
            UUID sourceId,
            long sourceVersion,
            UUID recipientId,
            com.actilazion.aries_transaction.notification.domain.NotificationType type
    );

    @Modifying
    @Query("DELETE FROM Notification notification WHERE notification.readAt IS NOT NULL AND notification.readAt < :cutoff")
    int deleteReadBefore(@Param("cutoff") OffsetDateTime cutoff);
}
