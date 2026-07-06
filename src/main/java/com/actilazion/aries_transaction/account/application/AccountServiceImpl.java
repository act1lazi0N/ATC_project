package com.actilazion.aries_transaction.account.application;

import com.actilazion.aries_transaction.account.dto.CreateAccountRequest;
import com.actilazion.aries_transaction.account.dto.AccountResponse;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountType;
import com.actilazion.aries_transaction.account.exception.InternalAccountTypeException;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.account.persistence.AccountRepository;
import com.actilazion.aries_transaction.identity.persistence.UserRepository;
import com.actilazion.aries_transaction.account.application.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public AccountResponse create(CreateAccountRequest request, String ownerEmail) {
        validatePublicAccountType(request.accountType());
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

        Account saved = accountRepository.save(account);
        log.info("[ACCOUNT] Created accountId={} owner={}", saved.getId(), ownerEmail);
        return AccountResponse.from(saved);
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
    public AccountResponse getById(UUID accountId) {
        return accountRepository.findById(accountId)
                .map(AccountResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
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
    public AccountResponse freeze(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        account.setStatus(AccountStatus.FROZEN);
        return AccountResponse.from(accountRepository.save(account));
    }

    private String generateAccountNumber() {
        String number;
        do {
            number = String.format("%012d", new Random().nextLong(1_000_000_000_000L));
        } while (accountRepository.existsByAccountNumber(number));
        return number;
    }
}
