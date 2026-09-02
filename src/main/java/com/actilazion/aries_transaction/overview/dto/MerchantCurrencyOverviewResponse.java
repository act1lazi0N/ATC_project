package com.actilazion.aries_transaction.overview.dto;

import java.util.List;

public record MerchantCurrencyOverviewResponse(
        String currency,
        String balance,
        String inflow,
        String outflow,
        String refunds,
        String pending,
        long pendingCount,
        String settlementNet,
        List<MerchantTrendPointResponse> trend
) {
}
