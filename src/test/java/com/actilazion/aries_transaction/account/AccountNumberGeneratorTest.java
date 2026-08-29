package com.actilazion.aries_transaction.account;

import com.actilazion.aries_transaction.account.application.AccountNumberGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountNumberGeneratorTest {
    @Test
    void generate_returnsTwelveDigitsWithValidLuhnCheckDigit() {
        AccountNumberGenerator generator = new AccountNumberGenerator();

        for (int attempt = 0; attempt < 100; attempt++) {
            String number = generator.generate();
            assertThat(number).hasSize(12).matches("\\d{12}");
            assertThat(AccountNumberGenerator.isValid(number)).isTrue();
        }
    }

    @Test
    void isValid_rejectsChangedCheckDigit() {
        assertThat(AccountNumberGenerator.isValid("799273987104")).isTrue();
        assertThat(AccountNumberGenerator.isValid("799273987103")).isFalse();
    }
}
