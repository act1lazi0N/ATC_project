package com.actilazion.aries_transaction.reconciliation.application;

import com.actilazion.aries_transaction.reconciliation.dto.ReconciliationRunResponse;
import com.actilazion.aries_transaction.reconciliation.dto.ReconciliationRunSummaryResponse;
import com.actilazion.aries_transaction.common.dto.PageResponse;
import org.springframework.data.domain.Pageable;

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

    PageResponse<ReconciliationRunSummaryResponse> getRuns(Pageable pageable, String initiatorEmail);
}
