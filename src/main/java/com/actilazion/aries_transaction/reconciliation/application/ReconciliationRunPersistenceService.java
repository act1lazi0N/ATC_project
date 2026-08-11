package com.actilazion.aries_transaction.reconciliation.application;

import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationRun;
import com.actilazion.aries_transaction.reconciliation.dto.ReconciliationRunResponse;
import com.actilazion.aries_transaction.reconciliation.infrastructure.ReconciliationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class ReconciliationRunPersistenceService implements ReconciliationRunPersistence {
    private final ReconciliationRunRepository reconciliationRunRepository;

    @Transactional
    @Override
    public ReconciliationRunResponse saveCompleted(
            ReconciliationRun run,
            int sourceTransactionCount,
            int reportingSnapshotCount
    ) {
        run.complete(sourceTransactionCount, reportingSnapshotCount, OffsetDateTime.now());
        return ReconciliationRunResponse.from(reconciliationRunRepository.save(run));
    }
}
