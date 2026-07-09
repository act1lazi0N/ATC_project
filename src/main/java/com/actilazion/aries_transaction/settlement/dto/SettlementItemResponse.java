package com.actilazion.aries_transaction.settlement.dto;

import com.actilazion.aries_transaction.settlement.domain.PayoutStatus;
import com.actilazion.aries_transaction.settlement.domain.SettlementItem;

import java.math.BigDecimal;
import java.util.UUID;

public record SettlementItemResponse(
        UUID id,
        UUID transactionId,
        UUID receiverAccountId,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        BigDecimal platformRevenue,
        BigDecimal receiverPayable,
        String currency,
        PayoutStatus payoutStatus
) {
    public static SettlementItemResponse from(SettlementItem item) {
        return new SettlementItemResponse(
                item.getId(),
                item.getTransaction().getId(),
                item.getReceiverAccount().getId(),
                item.getGrossAmount(),
                item.getFeeAmount(),
                item.getNetAmount(),
                item.getPlatformRevenue(),
                item.getReceiverPayable(),
                item.getCurrency(),
                item.getPayoutStatus()
        );
    }
}
