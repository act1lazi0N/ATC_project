package com.actilazion.aries_transaction.transaction.infrastructure;

import com.actilazion.aries_transaction.transaction.domain.AccountCreationRequestRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountCreationRequestRepository extends JpaRepository<AccountCreationRequestRecord, UUID> {
    Optional<AccountCreationRequestRecord> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
