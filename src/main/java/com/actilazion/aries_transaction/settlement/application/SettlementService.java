package com.actilazion.aries_transaction.settlement.application;

import com.actilazion.aries_transaction.settlement.dto.SettlementBatchResponse;

import java.util.UUID;

public interface SettlementService {
    SettlementBatchResponse createBatch(String currency, int feeRateBps);

    SettlementBatchResponse getBatch(UUID batchId);
}
