package com.actilazion.aries_transaction.payment.dto;

import com.actilazion.aries_transaction.payment.domain.QrType;
import com.actilazion.aries_transaction.transaction.dto.TransferPreviewResponse;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ResolvedQrResponse(UUID qrCodeId, QrType type, TransferPreviewResponse.MaskedAccount recipient,
                                 String amount, String currency, String description, OffsetDateTime expiresAt) {}
