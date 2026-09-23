package com.actilazion.aries_transaction.payment;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.payment.application.PaymentQrTransferGuard;
import com.actilazion.aries_transaction.payment.domain.PaymentQrCode;
import com.actilazion.aries_transaction.payment.domain.QrException;
import com.actilazion.aries_transaction.payment.domain.QrType;
import com.actilazion.aries_transaction.payment.infrastructure.PaymentQrRepository;
import com.actilazion.aries_transaction.transaction.domain.TransferPreview;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentQrTransferGuardTest {
    private final PaymentQrRepository repository = mock(PaymentQrRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final PaymentQrTransferGuard guard = new PaymentQrTransferGuard(repository, audit);

    @Test
    void revokedAfterInitialValidation_cannotBeMarkedPaid() {
        UUID destinationId = UUID.randomUUID();
        PaymentQrCode qr = PaymentQrCode.create(UUID.randomUUID(), destinationId, QrType.PAYMENT_REQUEST,
                new BigDecimal("1500.00"), "Fixed payment", "qr-key", OffsetDateTime.now());
        TransferPreview preview = TransferPreview.builder()
                .qrCodeId(qr.getId())
                .destinationAccount(Account.builder().id(destinationId).build())
                .amount(new BigDecimal("1500.00"))
                .currency("VND")
                .description("Fixed payment")
                .build();
        when(repository.findByIdWithLock(qr.getId())).thenReturn(Optional.of(qr));

        PaymentQrCode locked = guard.lockAndValidate(preview);
        qr.revoke(OffsetDateTime.now());

        assertThatThrownBy(() -> guard.complete(locked, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(QrException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((QrException) error).getCode())
                        .isEqualTo("QR_REVOKED"));
        verifyNoInteractions(audit);
    }

    @Test
    void fixedPaymentRequest_rejectsPreviewWithAnotherAmount() {
        UUID destinationId = UUID.randomUUID();
        PaymentQrCode qr = PaymentQrCode.create(UUID.randomUUID(), destinationId, QrType.PAYMENT_REQUEST,
                new BigDecimal("1500.00"), "Fixed payment", "qr-key", OffsetDateTime.now());
        TransferPreview preview = TransferPreview.builder()
                .qrCodeId(qr.getId())
                .destinationAccount(Account.builder().id(destinationId).build())
                .amount(new BigDecimal("2000.00"))
                .currency("VND")
                .description("Fixed payment")
                .build();
        when(repository.findByIdWithLock(qr.getId())).thenReturn(Optional.of(qr));

        assertThatThrownBy(() -> guard.lockAndValidate(preview))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("QR preview binding mismatch");
    }
}
