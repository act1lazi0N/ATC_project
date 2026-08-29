package com.actilazion.aries_transaction.account.application;

import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import com.actilazion.aries_transaction.account.dto.AccountResponse;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.domain.exception.AccountNumberGenerationException;
import com.actilazion.aries_transaction.account.domain.exception.InternalAccountTypeException;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.common.exception.ForbiddenOperationException;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {
    private static final int ACCOUNT_NUMBER_MAX_ATTEMPTS = 5;

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountCreationAttempt accountCreationAttempt;

    @Override
    public AccountResponse create(CreateAccountRequest request, String ownerEmail) {
        if (request == null) {
            throw new IllegalArgumentException("Create account request is required");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (request.accountType() == null) {
            throw new IllegalArgumentException("accountType is required");
        }
        validatePublicAccountType(request.accountType());
        if (!"VND".equals(request.currency())) {
            throw new IllegalArgumentException("Unsupported currency");
        }
        for (int attempt = 1; attempt <= ACCOUNT_NUMBER_MAX_ATTEMPTS; attempt++) {
            try {
                return accountCreationAttempt.create(request, ownerEmail);
            } catch (DataIntegrityViolationException ex) {
                if (!isAccountNumberCollision(ex)) {
                    throw ex;
                }
                if (attempt == ACCOUNT_NUMBER_MAX_ATTEMPTS) {
                    throw new AccountNumberGenerationException(ACCOUNT_NUMBER_MAX_ATTEMPTS, ex);
                }
                log.warn("[ACCOUNT] Account number collision, retrying attempt={}", attempt + 1);
            }
        }
        throw new AccountNumberGenerationException(ACCOUNT_NUMBER_MAX_ATTEMPTS, null);
    }

    private boolean isAccountNumberCollision(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return "uk_accounts_number".equals(violation.getConstraintName());
            }
            if (cause.getMessage() != null
                    && cause.getMessage().toLowerCase(java.util.Locale.ROOT).contains("uk_accounts_number")) {
                return true;
            }
        }
        return false;
    }

    private void validatePublicAccountType(AccountType accountType) {
        if (accountType == AccountType.CLEARING
                || accountType == AccountType.RECEIVER_PAYABLE
                || accountType == AccountType.PLATFORM_REVENUE) {
            throw new InternalAccountTypeException(accountType);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getById(UUID accountId, String requesterEmail) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        User requester = userRepository.findByEmail(requesterEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", requesterEmail));
        if (!account.getUser().getId().equals(requester.getId())
                && requester.getRole() != Role.OPERATOR
                && requester.getRole() != Role.ADMIN) {
            throw new ForbiddenOperationException("Not allowed to read this account");
        }
        return AccountResponse.from(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountResponse> getMyAccounts(String ownerEmail) {
        User owner = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", ownerEmail));
        return accountRepository.findAllByUserId(owner.getId())
                .stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public AccountResponse freeze(UUID accountId, String requesterEmail) {
        User requester = userRepository.findByEmail(requesterEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", requesterEmail));
        if (requester.getRole() != Role.OPERATOR
                && requester.getRole() != Role.ADMIN) {
            throw new ForbiddenOperationException("Not allowed to freeze this account");
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        account.setStatus(AccountStatus.FROZEN);
        return AccountResponse.from(accountRepository.save(account));
    }

}
