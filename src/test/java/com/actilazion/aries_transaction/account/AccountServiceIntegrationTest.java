package com.actilazion.aries_transaction.account;

import com.actilazion.aries_transaction.account.application.AccountService;
import com.actilazion.aries_transaction.account.application.AccountServiceImpl;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import com.actilazion.aries_transaction.account.domain.exception.InternalAccountTypeException;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import(AccountServiceImpl.class)
class AccountServiceIntegrationTest {
    @Autowired AccountService accountService;
    @Autowired UserRepository userRepository;

    private static final String OWNER_EMAIL = "account-owner@test.com";

    @BeforeEach
    void setUp() {
        userRepository.save(User.builder()
                .fullName("Account Owner")
                .email(OWNER_EMAIL)
                .passwordHash("hashed")
                .role(Role.USER)
                .build());
    }

    @Test
    @DisplayName("User account creation rejects internal account types")
    void create_internalAccountType_throwsException() {
        var request = new CreateAccountRequest(AccountType.CLEARING, "VND", null);

        assertThatThrownBy(() -> accountService.create(request, OWNER_EMAIL))
                .isInstanceOf(InternalAccountTypeException.class);
    }
}
