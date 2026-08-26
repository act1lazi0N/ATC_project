package com.actilazion.aries_transaction.account.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.domain.exception.AccountCreationIdempotencyConflictException;
import com.actilazion.aries_transaction.account.domain.exception.AccountLimitExceededException;
import com.actilazion.aries_transaction.account.dto.AccountResponse;
import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.transaction.domain.AccountCreationRequestRecord;
import com.actilazion.aries_transaction.transaction.infrastructure.AccountCreationRequestRepository;
import com.actilazion.aries_transaction.audit.domain.AuditEventType;
import com.actilazion.aries_transaction.audit.domain.AuditLog;
import com.actilazion.aries_transaction.audit.infrastructure.AuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class AccountCreationAttemptService implements AccountCreationAttempt {
    private static final long ACCOUNT_NUMBER_BOUND = 1_000_000_000_000L;

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountCreationRequestRepository requestRepository;
    private final AuditLogRepository auditLogRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public AccountCreationAttemptService(AccountRepository accountRepository,
                                         UserRepository userRepository,
                                         AccountCreationRequestRepository requestRepository,
                                         AuditLogRepository auditLogRepository) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public AccountResponse create(CreateAccountRequest request, String ownerEmail) {
        User owner = userRepository.findByEmailWithLock(ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", ownerEmail));
        String requestHash = AccountCreationFingerprint.hash(request);
        var existing = requestRepository.findByUserIdAndIdempotencyKey(owner.getId(), request.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(requestHash)) {
                throw new AccountCreationIdempotencyConflictException();
            }
            return AccountCreationResponseSnapshot.fromPayload(existing.get().getResponsePayload());
        }
        if (accountRepository.countByUserIdAndStatus(owner.getId(), AccountStatus.ACTIVE) >= 5) {
            throw new AccountLimitExceededException();
        }
        Account account = Account.builder()
                .user(owner)
                .accountNumber(generateAccountNumber())
                .accountType(request.accountType())
                .balance(BigDecimal.ZERO)
                .currency(request.currency())
                .description(request.description())
                .status(AccountStatus.ACTIVE)
                .build();

        Account saved = accountRepository.saveAndFlush(account);
        AccountResponse response = AccountResponse.from(saved);
        requestRepository.saveAndFlush(AccountCreationRequestRecord.builder()
                .user(owner)
                .idempotencyKey(request.idempotencyKey())
                .requestHash(requestHash)
                .responsePayload(AccountCreationResponseSnapshot.toPayload(response))
                .account(saved)
                .build());
        auditLogRepository.save(AuditLog.builder()
                .accountId(saved.getId())
                .eventType(AuditEventType.ACCOUNT_CREATED)
                .actorId(ownerEmail)
                .payload(java.util.Map.of("accountId", saved.getId().toString(), "currency", saved.getCurrency()))
                .build());
        log.info("[ACCOUNT] Created accountId={} owner={}", saved.getId(), ownerEmail);
        return response;
    }

    private String generateAccountNumber() {
        String number;
        do {
            number = String.format("%012d", ThreadLocalRandom.current().nextLong(ACCOUNT_NUMBER_BOUND));
        } while (accountRepository.existsByAccountNumber(number));
        return number;
    }
}
