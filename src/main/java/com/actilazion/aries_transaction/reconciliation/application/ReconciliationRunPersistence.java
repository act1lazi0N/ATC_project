package com.actilazion.aries_transaction.reconciliation.application;

import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationRun;
import com.actilazion.aries_transaction.reconciliation.dto.ReconciliationRunResponse;

public interface ReconciliationRunPersistence {
    ReconciliationRunResponse saveCompleted(
            ReconciliationRun run,
            int sourceTransactionCount,
            int reportingSnapshotCount
    );
}
