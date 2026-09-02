package com.actilazion.aries_transaction.overview.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record MerchantOverviewResponse(
        String range,
        String timezone,
        OffsetDateTime generatedAt,
        List<MerchantCurrencyOverviewResponse> currencies
) {
}
