package com.actilazion.aries_transaction.payment.dto;

import com.actilazion.aries_transaction.payment.domain.*;
import java.time.OffsetDateTime;
import java.util.UUID;

public record QrResponse(UUID id, String payload, QrType type, String state, String amount,
                         String currency, String description, OffsetDateTime createdAt,
                         OffsetDateTime expiresAt, UUID transactionId) {
    public static QrResponse from(PaymentQrCode qr) {
        return new QrResponse(qr.getId(), QrPayload.encode(qr.getId()), qr.getType(),
                qr.effectiveState(OffsetDateTime.now()), qr.getAmount() == null ? null : qr.getAmount().toPlainString(),
                qr.getCurrency(), qr.getDescription(), qr.getCreatedAt(), qr.getExpiresAt(), qr.getTransactionId());
    }
}
