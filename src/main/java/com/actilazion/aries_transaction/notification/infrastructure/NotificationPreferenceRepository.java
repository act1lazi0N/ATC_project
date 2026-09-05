package com.actilazion.aries_transaction.notification.infrastructure;

import com.actilazion.aries_transaction.notification.domain.NotificationPreference;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT preference FROM NotificationPreference preference WHERE preference.userId = :userId")
    Optional<NotificationPreference> findByUserIdForUpdate(@Param("userId") UUID userId);
}
