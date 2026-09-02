package com.actilazion.aries_transaction.settlement.infrastructure;

import com.actilazion.aries_transaction.settlement.domain.SettlementBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, UUID> {
    Optional<SettlementBatch> findByIdempotencyKey(String idempotencyKey);

    List<SettlementBatch> findAllByCreatedAtGreaterThanEqual(OffsetDateTime from);
}
