package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.transaction.domain.TransferAmountPolicy;
import com.actilazion.aries_transaction.transaction.domain.exception.InvalidTransferAmountException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferAmountPolicyTest {
    @Test
    void normalize_acceptsMinimumAndNormalizesToTwoDecimals() {
        assertThat(TransferAmountPolicy.normalize("1000")).isEqualByComparingTo("1000.00");
        assertThat(TransferAmountPolicy.normalize(new BigDecimal("1000.50"))).hasScaleOf(2);
    }

    @Test
    void normalize_rejectsBelowMinimum() {
        assertThatThrownBy(() -> TransferAmountPolicy.normalize("999.99"))
                .isInstanceOf(InvalidTransferAmountException.class);
    }

    @Test
    void normalize_rejectsMoreThanTwoDecimalPlacesEvenWhenTrailingZeros() {
        assertThatThrownBy(() -> TransferAmountPolicy.normalize(new BigDecimal("1000.000")))
                .isInstanceOf(InvalidTransferAmountException.class);
    }

    @Test
    void normalize_rejectsAmountsThatExceedDatabasePrecision() {
        assertThatThrownBy(() -> TransferAmountPolicy.normalize(new BigDecimal("10000000000000000")))
                .isInstanceOf(InvalidTransferAmountException.class);
    }
}
