package com.actilazion.aries_transaction.account;

import com.actilazion.aries_transaction.account.application.AccountCreationFingerprint;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountCreationFingerprintTest {
    @Test
    void hash_isStableForNullDescription() {
        var request = new CreateAccountRequest(AccountType.PERSONAL, "VND", null, "account-key-0001");

        assertThat(AccountCreationFingerprint.hash(request))
                .isEqualTo("8f47bb89c50f70b611994033f23c68bc78e781c7563614f59475d349e0c9d007");
    }

    @Test
    void hash_usesUtf8ByteLengthAndExcludesIdempotencyKey() {
        var first = new CreateAccountRequest(AccountType.PERSONAL, "VND", "Đầu tư", "account-key-0001");
        var replay = new CreateAccountRequest(AccountType.PERSONAL, "VND", "Đầu tư", "account-key-0002");

        assertThat(AccountCreationFingerprint.hash(first))
                .isEqualTo("c6dcc98969894a3d1f09213dd85c768f9af53614a184a843cebe291ca4fdb4f6")
                .isEqualTo(AccountCreationFingerprint.hash(replay));
    }

    @Test
    void hash_distinguishesNullAndEmptyDescription() {
        var absent = new CreateAccountRequest(AccountType.PERSONAL, "VND", null, "account-key-0001");
        var empty = new CreateAccountRequest(AccountType.PERSONAL, "VND", "", "account-key-0001");

        assertThat(AccountCreationFingerprint.hash(absent))
                .isNotEqualTo(AccountCreationFingerprint.hash(empty));
    }
}
