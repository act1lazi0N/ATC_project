package com.actilazion.aries_transaction.transaction.domain;

import com.actilazion.aries_transaction.transaction.domain.exception.InvalidTransferAmountException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TransferAmountPolicy {
    public static final BigDecimal MINIMUM_AMOUNT = new BigDecimal("1000.00");
    private static final int MAX_INTEGER_DIGITS = 16;
    private static final int MAX_FRACTION_DIGITS = 2;

    private TransferAmountPolicy() {
    }

    public static BigDecimal normalize(String rawAmount) {
        if (rawAmount == null) {
            throw new InvalidTransferAmountException();
        }
        try {
            return normalize(new BigDecimal(rawAmount));
        } catch (NumberFormatException ex) {
            throw new InvalidTransferAmountException();
        }
    }

    public static BigDecimal normalize(BigDecimal amount) {
        if (amount == null
                || amount.compareTo(MINIMUM_AMOUNT) < 0
                || amount.scale() > MAX_FRACTION_DIGITS
                || integerDigits(amount) > MAX_INTEGER_DIGITS) {
            throw new InvalidTransferAmountException();
        }
        try {
            return amount.setScale(MAX_FRACTION_DIGITS, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new InvalidTransferAmountException();
        }
    }

    private static int integerDigits(BigDecimal amount) {
        return Math.max(1, amount.precision() - amount.scale());
    }
}
