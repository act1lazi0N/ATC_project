package com.actilazion.aries_transaction.reconciliation.infrastructure;

import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
}
