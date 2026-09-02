package com.actilazion.aries_transaction.settlement.application;

import com.actilazion.aries_transaction.settlement.dto.SettlementBatchResponse;
import com.actilazion.aries_transaction.common.dto.PageResponse;
import com.actilazion.aries_transaction.settlement.dto.SettlementBatchSummaryResponse;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface SettlementService {
    SettlementBatchResponse createBatch(
            String currency,
            int feeRateBps,
            String idempotencyKey,
            OffsetDateTime cutoffCompletedAt,
            String initiatorEmail
    );

    SettlementBatchResponse getBatch(UUID batchId, String initiatorEmail);

    PageResponse<SettlementBatchSummaryResponse> getBatches(Pageable pageable, String initiatorEmail);
}
