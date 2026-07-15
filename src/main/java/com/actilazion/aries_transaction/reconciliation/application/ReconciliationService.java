package com.actilazion.aries_transaction.reconciliation.application;

import com.actilazion.aries_transaction.reconciliation.dto.ReconciliationRunResponse;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface ReconciliationService {
    ReconciliationRunResponse reconcile(
            String currency,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            String initiatorEmail
    );

    ReconciliationRunResponse getRun(UUID runId, String initiatorEmail);
}
