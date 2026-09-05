package com.actilazion.aries_transaction.identity.infrastructure;

import com.actilazion.aries_transaction.identity.domain.EmailVerificationChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface EmailVerificationChallengeRepository
        extends JpaRepository<EmailVerificationChallenge, UUID> {

    @EntityGraph(attributePaths = "user")
    List<EmailVerificationChallenge> findAllByUser_IdOrderByCreatedAtDesc(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT challenge FROM EmailVerificationChallenge challenge JOIN FETCH challenge.user WHERE challenge.id = :id")
    Optional<EmailVerificationChallenge> findByIdWithUserForUpdate(@Param("id") UUID id);

    @Modifying
    @Query("""
            UPDATE EmailVerificationChallenge challenge
            SET challenge.invalidatedAt = :now
            WHERE challenge.user.id = :userId
              AND challenge.consumedAt IS NULL
              AND challenge.invalidatedAt IS NULL
            """)
    int invalidateActiveByUserId(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);
}
