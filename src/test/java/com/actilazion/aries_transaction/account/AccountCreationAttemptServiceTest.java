package com.actilazion.aries_transaction.account;

import com.actilazion.aries_transaction.account.application.AccountCreationAttemptService;
import com.actilazion.aries_transaction.account.application.AccountCreationFingerprint;
import com.actilazion.aries_transaction.account.application.AccountCreationResponseSnapshot;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.domain.exception.AccountCreationIdempotencyConflictException;
import com.actilazion.aries_transaction.account.domain.exception.AccountLimitExceededException;
import com.actilazion.aries_transaction.account.dto.AccountResponse;
import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.infrastructure.AuditLogRepository;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.transaction.domain.AccountCreationRequestRecord;
import com.actilazion.aries_transaction.transaction.infrastructure.AccountCreationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountCreationAttemptServiceTest {
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AccountCreationRequestRepository requestRepository = mock(AccountCreationRequestRepository.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final AccountCreationAttemptService service = new AccountCreationAttemptService(
            accountRepository, userRepository, requestRepository, auditLogRepository);

    private User owner;
    private CreateAccountRequest request;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(UUID.randomUUID()).fullName("Owner").email("owner@test.local")
                .passwordHash("hashed").role(Role.USER).build();
        request = new CreateAccountRequest(AccountType.PERSONAL, "VND", "Original", "account-key-0001");
        when(userRepository.findByEmailWithLock(owner.getEmail())).thenReturn(Optional.of(owner));
    }

    @Test
    void create_sameRequestReplaysImmutableSnapshot() {
        AccountResponse original = new AccountResponse(
                UUID.randomUUID(), owner.getId(), "123456789012", AccountType.PERSONAL,
                BigDecimal.ZERO.setScale(2), "VND", AccountStatus.ACTIVE,
                OffsetDateTime.parse("2026-08-25T12:00:00Z"), "Original");
        AccountCreationRequestRecord record = AccountCreationRequestRecord.builder()
                .requestHash(AccountCreationFingerprint.hash(request))
                .responsePayload(AccountCreationResponseSnapshot.toPayload(original))
                .build();
        when(requestRepository.findByUserIdAndIdempotencyKey(owner.getId(), request.idempotencyKey()))
                .thenReturn(Optional.of(record));

        AccountResponse replay = service.create(request, owner.getEmail());

        assertThat(replay).isEqualTo(original);
        verify(accountRepository, never()).countByUserIdAndStatus(owner.getId(), AccountStatus.ACTIVE);
        verify(accountRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void create_differentRequestWithSameKeyThrowsStableConflict() {
        AccountCreationRequestRecord record = AccountCreationRequestRecord.builder()
                .requestHash(AccountCreationFingerprint.hash(request))
                .responsePayload(java.util.Map.of())
                .build();
        when(requestRepository.findByUserIdAndIdempotencyKey(owner.getId(), request.idempotencyKey()))
                .thenReturn(Optional.of(record));
        var changed = new CreateAccountRequest(AccountType.BUSINESS, "VND", "Original", request.idempotencyKey());

        assertThatThrownBy(() -> service.create(changed, owner.getEmail()))
                .isInstanceOf(AccountCreationIdempotencyConflictException.class);
    }

    @Test
    void create_atFiveActiveAccountsThrowsStableLimitError() {
        when(requestRepository.findByUserIdAndIdempotencyKey(owner.getId(), request.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(accountRepository.countByUserIdAndStatus(owner.getId(), AccountStatus.ACTIVE)).thenReturn(5L);

        assertThatThrownBy(() -> service.create(request, owner.getEmail()))
                .isInstanceOf(AccountLimitExceededException.class);
    }
}
