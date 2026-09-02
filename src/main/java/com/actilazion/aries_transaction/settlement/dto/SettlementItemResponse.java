package com.actilazion.aries_transaction.settlement.dto;

import com.actilazion.aries_transaction.settlement.domain.PayoutStatus;
import com.actilazion.aries_transaction.settlement.domain.SettlementItem;
import com.actilazion.aries_transaction.settlement.domain.SettlementItemType;

import java.math.BigDecimal;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record SettlementItemResponse(
        UUID id,
        UUID transactionId,
        UUID receiverAccountId,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal grossAmount,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal feeAmount,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal netAmount,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal platformRevenue,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal receiverPayable,
        SettlementItemType itemType,
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
                item.getItemType(),
                item.getCurrency(),
                item.getPayoutStatus()
        );
    }
}
