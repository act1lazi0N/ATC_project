package com.actilazion.aries_transaction.payment.domain;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_qr_codes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentQrCode {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID ownerId;
    @Column(nullable = false, updatable = false) private UUID accountId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private QrType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private QrState state;
    @Column(precision = 18, scale = 2, updatable = false) private BigDecimal amount;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Column(length = 255, updatable = false) private String description;
    @Column(nullable = false, length = 64, updatable = false) private String idempotencyKey;
    @Column(nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(updatable = false) private OffsetDateTime expiresAt;
    private OffsetDateTime revokedAt;
    private OffsetDateTime paidAt;
    private UUID transactionId;

    public static PaymentQrCode create(UUID ownerId, UUID accountId, QrType type, BigDecimal amount,
                                       String description, String key, OffsetDateTime now) {
        now = now.withOffsetSameInstant(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        PaymentQrCode qr = new PaymentQrCode();
        qr.id = UUID.randomUUID();
        qr.ownerId = ownerId;
        qr.accountId = accountId;
        qr.type = type;
        qr.state = QrState.ACTIVE;
        qr.amount = amount;
        qr.currency = "VND";
        qr.description = description;
        qr.idempotencyKey = key;
        qr.createdAt = now;
        qr.expiresAt = type == QrType.PAYMENT_REQUEST ? now.plusMinutes(15) : null;
        return qr;
    }

    public String effectiveState(OffsetDateTime now) {
        return state == QrState.ACTIVE && expiresAt != null && !expiresAt.isAfter(now) ? "EXPIRED" : state.name();
    }

    public void requirePayable(OffsetDateTime now) {
        String effective = effectiveState(now);
        if (!"ACTIVE".equals(effective)) {
            throw new QrException("QR_" + effective, "QR code is " + effective.toLowerCase(java.util.Locale.ROOT), HttpStatus.CONFLICT);
        }
    }

    public boolean revoke(OffsetDateTime now) {
        if (state == QrState.REVOKED) return false;
        if (state == QrState.PAID) {
            throw new QrException("QR_PAID", "Paid QR cannot be revoked", HttpStatus.CONFLICT);
        }
        state = QrState.REVOKED;
        revokedAt = now;
        return true;
    }

    public void markPaid(UUID transactionId, OffsetDateTime now) {
        requirePayable(now);
        if (type != QrType.PAYMENT_REQUEST) throw new IllegalStateException("Only payment requests are consumed");
        this.transactionId = java.util.Objects.requireNonNull(transactionId);
        paidAt = now;
        state = QrState.PAID;
    }
}
