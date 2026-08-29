package com.actilazion.aries_transaction.account.application;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates immutable customer-facing account numbers.
 *
 * <p>The database unique constraint remains the source of truth for
 * uniqueness. The Luhn digit only detects common transcription errors.</p>
 */
@Component
public final class AccountNumberGenerator {
    private static final int BODY_LENGTH = 11;
    private final SecureRandom secureRandom;

    public AccountNumberGenerator() {
        this(new SecureRandom());
    }

    public AccountNumberGenerator(SecureRandom secureRandom) {
        this.secureRandom = java.util.Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public String generate() {
        StringBuilder body = new StringBuilder(BODY_LENGTH);
        for (int index = 0; index < BODY_LENGTH; index++) {
            body.append(secureRandom.nextInt(10));
        }
        String bodyValue = body.toString();
        return bodyValue + luhnCheckDigit(bodyValue);
    }

    static int luhnCheckDigit(String body) {
        if (body == null || body.length() != BODY_LENGTH
                || !body.chars().allMatch(character -> character >= '0' && character <= '9')) {
            throw new IllegalArgumentException("Account number body must contain 11 decimal digits");
        }
        int sum = 0;
        boolean doubleDigit = true;
        for (int index = body.length() - 1; index >= 0; index--) {
            int digit = body.charAt(index) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return (10 - (sum % 10)) % 10;
    }

    public static boolean isValid(String accountNumber) {
        if (accountNumber == null || accountNumber.length() != BODY_LENGTH + 1
                || !accountNumber.chars().allMatch(character -> character >= '0' && character <= '9')) {
            return false;
        }
        String body = accountNumber.substring(0, BODY_LENGTH);
        return luhnCheckDigit(body) == accountNumber.charAt(BODY_LENGTH) - '0';
    }
}
