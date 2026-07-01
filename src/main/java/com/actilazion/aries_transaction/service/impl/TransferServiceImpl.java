package com.actilazion.aries_transaction.service.impl;

import com.actilazion.aries_transaction.dto.requests.TransferRequest;
import com.actilazion.aries_transaction.dto.responses.TransactionResponse;
import com.actilazion.aries_transaction.entity.Account;
import com.actilazion.aries_transaction.entity.Transaction;
import com.actilazion.aries_transaction.entity.User;
import com.actilazion.aries_transaction.entity.enums.AccountStatus;
import com.actilazion.aries_transaction.entity.enums.AuditEventType;
import com.actilazion.aries_transaction.entity.enums.TransactionStatus;
import com.actilazion.aries_transaction.exception.AccountNotActiveException;
import com.actilazion.aries_transaction.exception.DuplicateTransferException;
import com.actilazion.aries_transaction.exception.IdempotencyConflictException;
import com.actilazion.aries_transaction.exception.InsufficientBalanceException;
import com.actilazion.aries_transaction.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.exception.SelfTransferException;
import com.actilazion.aries_transaction.repository.AccountRepository;
import com.actilazion.aries_transaction.repository.TransactionRepository;
import com.actilazion.aries_transaction.repository.UserRepository;
import com.actilazion.aries_transaction.service.AuditLogService;
import com.actilazion.aries_transaction.service.IdempotencyService;
import com.actilazion.aries_transaction.service.OutboxEventService;
import com.actilazion.aries_transaction.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;
    private final OutboxEventService outboxEventService;

    @Override
    @Transactional
    public TransactionResponse transfer(TransferRequest request, String initiatorEmail) {
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return responseForIdempotentRetry(existing.get(), request);
        }

        boolean acquired = idempotencyService.tryConsume(request.idempotencyKey());
        if (!acquired) {
            return transactionRepository.findByIdempotencyKey(request.idempotencyKey())
                    .map(tx -> responseForIdempotentRetry(tx, request))
                    .orElseThrow(() -> new DuplicateTransferException(request.idempotencyKey()));
        }

        try {
            return doTransfer(request, initiatorEmail);
        } catch (Exception e) {
            idempotencyService.release(request.idempotencyKey());
            throw e;
        }
    }

    private TransactionResponse responseForIdempotentRetry(Transaction tx, TransferRequest request) {
        if (!matchesOriginalRequest(tx, request)) {
            throw new IdempotencyConflictException(request.idempotencyKey());
        }
        return TransactionResponse.from(tx);
    }

    private boolean matchesOriginalRequest(Transaction tx, TransferRequest request) {
        UUID requestFromId = UUID.fromString(request.fromAccountId());
        UUID requestToId = UUID.fromString(request.toAccountId());
        String requestCurrency = request.currency() != null ? request.currency() : tx.getCurrency();

        return tx.getFromAccount().getId().equals(requestFromId)
                && tx.getToAccount().getId().equals(requestToId)
                && tx.getAmount().compareTo(request.amount()) == 0
                && tx.getCurrency().equals(requestCurrency)
                && Objects.equals(tx.getDescription(), request.description());
    }

    private TransactionResponse doTransfer(TransferRequest request, String initiatorEmail) {
        UUID fromId = UUID.fromString(request.fromAccountId());
        UUID toId = UUID.fromString(request.toAccountId());

        if (fromId.equals(toId)) {
            throw new SelfTransferException("Self transfer is not allowed");
        }

        Account fromAccount;
        Account toAccount;
        if (fromId.compareTo(toId) < 0) {
            fromAccount = lockAccount(fromId);
            toAccount = lockAccount(toId);
        } else {
            toAccount = lockAccount(toId);
            fromAccount = lockAccount(fromId);
        }

        validateAccountActive(fromAccount);
        validateAccountActive(toAccount);
        validateSufficientBalance(fromAccount, request.amount());

        User initiator = userRepository.findByEmail(initiatorEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", initiatorEmail));

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

        fromAccount.setBalance(fromAccount.getBalance().subtract(request.amount()));
        toAccount.setBalance(toAccount.getBalance().add(request.amount()));

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setCompletedAt(OffsetDateTime.now());
        transactionRepository.save(tx);
        transactionRepository.flush();

        outboxEventService.recordTransferCompleted(tx);

        auditLogService.log(tx, AuditEventType.TRANSFER_COMPLETED, initiatorEmail);
        log.info("[TRANSFER] COMPLETED txId={} from={} to={} amount={}", tx.getId(), fromId, toId, request.amount());

        return TransactionResponse.from(tx);
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionResponse getById(UUID txId) {
        Transaction tx = transactionRepository.findById(txId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", txId));
        return TransactionResponse.from(tx);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransactionResponse> getByAccount(UUID accountId, Pageable pageable) {
        return transactionRepository
                .findAllByAccountId(accountId, pageable)
                .map(TransactionResponse::from);
    }

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
