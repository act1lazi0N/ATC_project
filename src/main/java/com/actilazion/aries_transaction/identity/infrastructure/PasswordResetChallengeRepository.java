package com.actilazion.aries_transaction.identity.infrastructure;

import com.actilazion.aries_transaction.identity.domain.PasswordResetChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetChallengeRepository extends JpaRepository<PasswordResetChallenge, UUID> {
    @Query("select c.user.id from PasswordResetChallenge c where c.id = :id")
    Optional<UUID> findUserId(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PasswordResetChallenge c where c.id = :id")
    Optional<PasswordResetChallenge> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = "user")
    List<PasswordResetChallenge> findAllByUser_IdOrderByCreatedAtDesc(UUID userId);

    @Modifying(flushAutomatically = true)
    @Query("update PasswordResetChallenge c set c.invalidatedAt = :now "
            + "where c.user.id = :userId and c.consumedAt is null and c.invalidatedAt is null")
    int invalidateActiveByUserId(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    @Modifying
    @Query(value = """
            DELETE FROM password_reset_challenges c
            WHERE GREATEST(c.expires_at, c.consumed_at, c.invalidated_at) < :cutoff
              AND NOT EXISTS (SELECT 1 FROM email_deliveries d WHERE d.password_reset_challenge_id = c.id)
            """, nativeQuery = true)
    int deleteResolvedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
