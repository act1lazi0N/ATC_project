package com.actilazion.aries_transaction.account.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.dto.AccountResponse;
import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountCreationAttemptService {
    private static final long ACCOUNT_NUMBER_BOUND = 1_000_000_000_000L;

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AccountResponse create(CreateAccountRequest request, String ownerEmail) {
        User owner = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", ownerEmail));
        Account account = Account.builder()
                .user(owner)
                .accountNumber(generateAccountNumber())
                .accountType(request.accountType())
                .balance(BigDecimal.ZERO)
                .currency(request.currency())
                .status(AccountStatus.ACTIVE)
                .build();

        Account saved = accountRepository.saveAndFlush(account);
        log.info("[ACCOUNT] Created accountId={} owner={}", saved.getId(), ownerEmail);
        return AccountResponse.from(saved);
    }

    private String generateAccountNumber() {
        String number;
        do {
            number = String.format("%012d", ThreadLocalRandom.current().nextLong(ACCOUNT_NUMBER_BOUND));
        } while (accountRepository.existsByAccountNumber(number));
        return number;
    }
}
