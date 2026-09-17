package com.actilazion.aries_transaction.payment;

import com.actilazion.aries_transaction.payment.domain.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class QrDomainTest {
    @Test
    void payloadAcceptsOnlyCanonicalAriesV1UuidLocators() {
        UUID id = UUID.randomUUID();
        assertThat(QrPayload.parse(QrPayload.encode(id))).isEqualTo(id);
        for (String invalid : new String[]{"https://example.com/" + id, "aries:pay:v2:" + id,
                "aries:pay:v1:1-1-1-1-1", "aries:pay:v1:" + id + " ", "x".repeat(257), "aries:pay:v1:" + id.toString().toUpperCase()}) {
            assertThatThrownBy(() -> QrPayload.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> QrPayload.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiryIsExclusiveAndPaidAndRevokedAreTerminal() {
        var now = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var qr = payment(now);
        qr.requirePayable(now.plusMinutes(15).minusNanos(1));
        assertThatThrownBy(() -> qr.requirePayable(now.plusMinutes(15)))
                .isInstanceOfSatisfying(QrException.class, ex -> assertThat(ex.getCode()).isEqualTo("QR_EXPIRED"));
        qr.markPaid(UUID.randomUUID(), now.plusMinutes(1));
        assertThat(qr.effectiveState(now.plusDays(1))).isEqualTo("PAID");
        assertThatThrownBy(() -> qr.markPaid(UUID.randomUUID(), now)).isInstanceOf(QrException.class);
        assertThatThrownBy(() -> qr.revoke(now)).isInstanceOf(QrException.class);
        var revoked = payment(now);
        assertThat(revoked.revoke(now)).isTrue();
        assertThat(revoked.revoke(now)).isFalse();
        assertThatThrownBy(() -> revoked.requirePayable(now)).isInstanceOf(QrException.class);
    }

    private PaymentQrCode payment(OffsetDateTime now) {
        return PaymentQrCode.create(UUID.randomUUID(), UUID.randomUUID(), QrType.PAYMENT_REQUEST,
                new BigDecimal("1500.00"), "Lunch", UUID.randomUUID().toString(), now);
    }
}
