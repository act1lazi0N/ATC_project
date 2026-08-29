package com.actilazion.aries_transaction.account;

import com.actilazion.aries_transaction.account.application.AccountServiceImpl;
import com.actilazion.aries_transaction.account.application.AccountCreationAttemptService;
import com.actilazion.aries_transaction.account.application.AccountCreationPolicyProperties;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.domain.exception.AccountNumberGenerationException;
import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.transaction.infrastructure.AccountCreationRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceImplTest {
    private static final String OWNER_EMAIL = "account-owner@test.com";

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AccountCreationRequestRepository requestRepository = mock(AccountCreationRequestRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AccountCreationPolicyProperties policyProperties = new AccountCreationPolicyProperties();
    private final AccountServiceImpl accountService = new AccountServiceImpl(
            accountRepository,
            userRepository,
            new AccountCreationAttemptService(
                    accountRepository, userRepository, requestRepository, auditLogService, policyProperties)
    );

    @Test
    void create_retriesWhenGeneratedAccountNumberCollidesAtDatabase() {
        User owner = owner();
        when(userRepository.findByEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(owner));
        when(requestRepository.findByUserIdAndIdempotencyKey(owner.getId(), "account-key-0001"))
                .thenReturn(Optional.empty());
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key uk_accounts_number"))
                .thenAnswer(invocation -> persist(invocation.getArgument(0)));

        var response = accountService.create(
                new CreateAccountRequest(AccountType.PERSONAL, "VND", null, "account-key-0001"), OWNER_EMAIL);

        assertThat(response.id()).isNotNull();
        assertThat(response.accountNumber()).hasSize(12);
        verify(accountRepository, times(2)).saveAndFlush(any(Account.class));
    }

    @Test
    void create_exhaustedAccountNumberCollisions_throwsDomainException() {
        User owner = owner();
        when(userRepository.findByEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(owner));
        when(requestRepository.findByUserIdAndIdempotencyKey(owner.getId(), "account-key-0002"))
                .thenReturn(Optional.empty());
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key uk_accounts_number"));

        assertThatThrownBy(() -> accountService.create(
                new CreateAccountRequest(AccountType.PERSONAL, "VND", null, "account-key-0002"),
                OWNER_EMAIL
        )).isInstanceOf(AccountNumberGenerationException.class);

        verify(accountRepository, times(5)).saveAndFlush(any(Account.class));
    }

    @Test
    void create_propagatesIntegrityViolationForOtherConstraint() {
        User owner = owner();
        when(userRepository.findByEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(owner));
        when(requestRepository.findByUserIdAndIdempotencyKey(owner.getId(), "account-key-0003"))
                .thenReturn(Optional.empty());
        DataIntegrityViolationException violation = new DataIntegrityViolationException("uk_accounts_owner");
        when(accountRepository.saveAndFlush(any(Account.class))).thenThrow(violation);

        assertThatThrownBy(() -> accountService.create(
                new CreateAccountRequest(AccountType.PERSONAL, "VND", null, "account-key-0003"),
                OWNER_EMAIL
        )).isSameAs(violation);

        verify(accountRepository, times(1)).saveAndFlush(any(Account.class));
    }

    @Test
    void getMyAccounts_queriesAccountsByAuthenticatedOwner() {
        User owner = owner();
        Account account = Account.builder()
                .id(UUID.randomUUID())
                .user(owner)
                .accountNumber("123456789012")
                .accountType(AccountType.PERSONAL)
                .balance(new java.math.BigDecimal("1000000.00"))
                .currency("VND")
                .build();
        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner));
        when(accountRepository.findAllByUserId(owner.getId())).thenReturn(List.of(account));

        var accounts = accountService.getMyAccounts(OWNER_EMAIL);

        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).id()).isEqualTo(account.getId());
        verify(accountRepository).findAllByUserId(owner.getId());
    }

    @Test
    void accountCreationAttempt_usesRequiresNewTransaction() throws NoSuchMethodException {
        Transactional transactional = AccountCreationAttemptService.class
                .getMethod("create", CreateAccountRequest.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
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

}
