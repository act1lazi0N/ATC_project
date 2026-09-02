package com.actilazion.aries_transaction.reconciliation.infrastructure;

import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
    List<ReconciliationRun> findAllByCreatedAtGreaterThanEqual(OffsetDateTime from);
}
