package com.actilazion.aries_transaction.operations.dto;

import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;

import java.util.List;

public record CustomerDetailResponse(
        CustomerSummaryResponse customer,
        List<CustomerAccountResponse> accounts,
        List<TransactionResponse> recentActivity
) {
}
