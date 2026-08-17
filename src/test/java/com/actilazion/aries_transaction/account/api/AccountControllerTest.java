package com.actilazion.aries_transaction.account.api;

import com.actilazion.aries_transaction.account.application.AccountService;
import com.actilazion.aries_transaction.account.dto.AccountResponse;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {
    private static final String OWNER_EMAIL = "account-owner@test.com";

    @Mock
    AccountService accountService;

    @InjectMocks
    AccountController accountController;

    @Test
    void getMyAccounts_returnsAuthenticatedUsersAccounts() {
        UserDetails principal = User.withUsername(OWNER_EMAIL)
                .password("unused")
                .roles("USER")
                .build();
        AccountResponse account = new AccountResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "123456789012",
                AccountType.PERSONAL,
                new BigDecimal("1000000.00"),
                "VND",
                AccountStatus.ACTIVE,
                OffsetDateTime.parse("2026-08-17T00:00:00+07:00")
        );
        when(accountService.getMyAccounts(OWNER_EMAIL)).thenReturn(List.of(account));

        var result = accountController.getMyAccounts(principal);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().isSuccess()).isTrue();
        assertThat(result.getBody().getData()).containsExactly(account);
        verify(accountService).getMyAccounts(OWNER_EMAIL);
    }
}
