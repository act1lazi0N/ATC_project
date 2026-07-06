package com.actilazion.aries_transaction.settlement.application;

import com.actilazion.aries_transaction.settlement.dto.SettlementBatchResponse;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface SettlementService {
    SettlementBatchResponse createBatch(
            String currency,
            int feeRateBps,
            String idempotencyKey,
            OffsetDateTime cutoffCompletedAt
    );

    SettlementBatchResponse getBatch(UUID batchId);
}
