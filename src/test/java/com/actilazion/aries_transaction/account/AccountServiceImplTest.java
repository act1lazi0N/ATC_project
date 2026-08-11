package com.actilazion.aries_transaction.account;

import com.actilazion.aries_transaction.account.application.AccountServiceImpl;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.domain.exception.AccountNumberGenerationException;
import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceImplTest {
    private static final String OWNER_EMAIL = "account-owner@test.com";

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AccountServiceImpl accountService = new AccountServiceImpl(
            accountRepository,
            userRepository,
            new PassthroughTransactionManager()
    );

    @Test
    void create_retriesWhenGeneratedAccountNumberCollidesAtDatabase() {
        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner()));
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate account number"))
                .thenAnswer(invocation -> persist(invocation.getArgument(0)));

        var response = accountService.create(new CreateAccountRequest(AccountType.PERSONAL, "VND", null), OWNER_EMAIL);

        assertThat(response.id()).isNotNull();
        assertThat(response.accountNumber()).hasSize(12);
        verify(accountRepository, times(2)).saveAndFlush(any(Account.class));
    }

    @Test
    void create_exhaustedAccountNumberCollisions_throwsDomainException() {
        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner()));
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate account number"));

        assertThatThrownBy(() -> accountService.create(
                new CreateAccountRequest(AccountType.PERSONAL, "VND", null),
                OWNER_EMAIL
        )).isInstanceOf(AccountNumberGenerationException.class);

        verify(accountRepository, times(5)).saveAndFlush(any(Account.class));
    }

    private User owner() {
        return User.builder()
                .id(UUID.randomUUID())
                .fullName("Account Owner")
                .email(OWNER_EMAIL)
                .passwordHash("hashed")
                .role(Role.USER)
                .build();
    }

    private Account persist(Account account) {
        account.setId(UUID.randomUUID());
        return account;
    }

    private static final class PassthroughTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
