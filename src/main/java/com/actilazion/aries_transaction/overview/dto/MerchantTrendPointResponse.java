package com.actilazion.aries_transaction.overview.dto;

import java.time.LocalDate;

public record MerchantTrendPointResponse(
        LocalDate date,
        String inflow,
        String outflow
) {
}
