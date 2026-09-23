package com.actilazion.aries_transaction.payment.application;

import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.audit.domain.AuditEventType;
import com.actilazion.aries_transaction.payment.domain.PaymentQrCode;
import com.actilazion.aries_transaction.payment.domain.QrException;
import com.actilazion.aries_transaction.payment.domain.QrType;
import com.actilazion.aries_transaction.payment.infrastructure.PaymentQrRepository;
import com.actilazion.aries_transaction.transaction.domain.TransferPreview;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PaymentQrTransferGuard {
    private final PaymentQrRepository paymentQrRepository;
    private final AuditLogService auditLogService;

    public PaymentQrCode lockAndValidate(TransferPreview preview) {
        if (preview.getQrCodeId() == null) {
            return null;
        }
        PaymentQrCode qr = paymentQrRepository.findByIdWithLock(preview.getQrCodeId())
                .orElseThrow(QrException::unavailable);
        qr.requirePayable(OffsetDateTime.now());
        if (!qr.getAccountId().equals(preview.getDestinationAccount().getId())
                || !qr.getCurrency().equals(preview.getCurrency())
                || (qr.getType() == QrType.PAYMENT_REQUEST
                    && (qr.getAmount().compareTo(preview.getAmount()) != 0
                        || !Objects.equals(qr.getDescription(), preview.getDescription())))) {
            throw new IllegalArgumentException("QR preview binding mismatch");
        }
        return qr;
    }

    public void complete(PaymentQrCode qr, UUID transactionId, UUID initiatorId) {
        if (qr == null) {
            return;
        }
        // Recheck after waiting for account locks; expiry rolls the transfer back.
        qr.requirePayable(OffsetDateTime.now());
        if (qr.getType() == QrType.PAYMENT_REQUEST) {
            qr.markPaid(transactionId, OffsetDateTime.now());
            auditLogService.log(qr, AuditEventType.QR_PAID, initiatorId.toString());
        }
    }
}
