package com.actilazion.aries_transaction.service;

import com.actilazion.aries_transaction.dto.requests.TransferRequest;
import com.actilazion.aries_transaction.dto.responses.TransactionResponse;
import com.actilazion.aries_transaction.entity.Account;
import com.actilazion.aries_transaction.entity.Transaction;
import com.actilazion.aries_transaction.entity.User;
import com.actilazion.aries_transaction.entity.enums.AccountStatus;
import com.actilazion.aries_transaction.entity.enums.AuditEventType;
import com.actilazion.aries_transaction.entity.enums.TransactionStatus;
import com.actilazion.aries_transaction.exception.*;
import com.actilazion.aries_transaction.repository.AccountRepository;
import com.actilazion.aries_transaction.repository.TransactionRepository;
import com.actilazion.aries_transaction.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;

    public TransactionResponse transfer(TransferRequest request, String initiatorEmail) {
        // Idempotency redis check
        boolean acquired = idempotencyService.tryConsume(request.idempotencyKey());
        if (!acquired) {
            throw new DuplicateTransferException(request.idempotencyKey());
        }
        try {
            return executeTransfer(request, initiatorEmail);
        } catch (DuplicateTransferException e) {
            throw e;
        } catch (Exception e) {
            idempotencyService.release(request.idempotencyKey());
            throw e;
        }
    }

    // Begin transaction
    @Transactional
    protected TransactionResponse executeTransfer(TransferRequest request, String initiatorEmail) {
        UUID fromId = UUID.fromString(request.fromAccountId());
        UUID toId = UUID.fromString(request.toAccountId());

        // Validate anti self-transfer
        if (fromId.equals(toId)) {
            throw new SelfTransferException("Self transfer is not allowed");
        }

        // Load & lock accounts
        // Lock UUID accounts in ascending order to avoid deadlock
        // when 2 requests are sent at the same time
        Account fromAccount;
        Account toAccount;
        if (fromId.compareTo(toId) < 0) {
            fromAccount = lockAccount(fromId);
            toAccount = lockAccount(toId);
        } else {
            toAccount = lockAccount(toId);
            fromAccount = lockAccount(fromId);
        }

        // Validate business rules
        validateAccountActive(fromAccount);
        validateAccountActive(toAccount);
        validateSufficientBalance(fromAccount, request.amount());

        // Load initiator
        User initiator = userRepository.findByEmail(initiatorEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", initiatorEmail));

        // Create transaction record with PENDING status
        Transaction tx = Transaction.builder()
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .initiatedBy(initiator)
                .amount(request.amount())
                .currency(request.currency() != null ? request.currency() : fromAccount.getCurrency())
                .idempotencyKey(request.idempotencyKey())
                .description(request.description())
                .status(TransactionStatus.PENDING)
                .build();
        transactionRepository.save(tx);
        auditLogService.log(tx, AuditEventType.TRANSFER_INITIATED, initiatorEmail);

        // Debit sender, Credit receiver
        // Using subtract/add on BigDecimal
        fromAccount.setBalance(fromAccount.getBalance().subtract(request.amount()));
        toAccount.setBalance(toAccount.getBalance().add(request.amount()));

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        // Mark COMPLETED
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setCompletedAt(OffsetDateTime.now());
        transactionRepository.save(tx);

        auditLogService.log(tx, AuditEventType.TRANSFER_COMPLETED, initiatorEmail);
        log.info("[TRANSFER] COMPLETED txId={} from={} to={} amount={}", tx.getId(), fromId, toId, request.amount());

        return TransactionResponse.from(tx);
    }

    // Query methods
    @Transactional(readOnly = true)
    public TransactionResponse getById(UUID txId) {
        Transaction tx = transactionRepository.findById(txId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", txId));
        return TransactionResponse.from(tx);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getByAccount(UUID accountId, Pageable pageable) {
        return transactionRepository
                .findAllByAccountId(accountId, pageable)
                .map(TransactionResponse::from);
    }

    // Private helpers
    private Account lockAccount(UUID accountId) {
        return accountRepository.findByIdWithLock(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    private void validateAccountActive(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(account.getId(), account.getStatus());
        }
    }

    private void validateSufficientBalance(Account account, BigDecimal required) {
        if (account.getBalance().compareTo(required) < 0) {
            throw new InsufficientBalanceException(account.getBalance(), required);
        }
    }

}
