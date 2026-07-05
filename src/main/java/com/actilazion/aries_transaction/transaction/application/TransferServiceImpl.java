package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.audit.domain.AuditEventType;
import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecord;
import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecordStatus;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.exception.AccountNotActiveException;
import com.actilazion.aries_transaction.transaction.exception.CurrencyMismatchException;
import com.actilazion.aries_transaction.transaction.exception.DuplicateTransferException;
import com.actilazion.aries_transaction.transaction.exception.IdempotencyConflictException;
import com.actilazion.aries_transaction.transaction.exception.InsufficientBalanceException;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.transaction.exception.SelfTransferException;
import com.actilazion.aries_transaction.account.persistence.AccountRepository;
import com.actilazion.aries_transaction.transaction.persistence.TransactionRepository;
import com.actilazion.aries_transaction.identity.persistence.UserRepository;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.transaction.application.IdempotencyService;
import com.actilazion.aries_transaction.ledger.application.LedgerService;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.transaction.application.TransferService;
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
public class TransferServiceImpl implements TransferService {
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;
    private final OutboxEventService outboxEventService;
    private final LedgerService ledgerService;

    @Override
    @Transactional
    public TransactionResponse transfer(TransferRequest request, String initiatorEmail) {
        var existing = idempotencyService.findByKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return responseForIdempotentRetry(existing.get(), request);
        }

        IdempotencyRecord record = idempotencyService.createProcessingRecord(request);
        TransactionResponse response = doTransfer(request, initiatorEmail);
        Transaction tx = transactionRepository.getReferenceById(response.id());
        idempotencyService.markCompleted(record, tx, response);
        return response;
    }

    private TransactionResponse responseForIdempotentRetry(IdempotencyRecord record, TransferRequest request) {
        if (!idempotencyService.matchesRequest(record, request)) {
            throw new IdempotencyConflictException(request.idempotencyKey());
        }

        if (record.getStatus() == IdempotencyRecordStatus.COMPLETED && record.getTransaction() != null) {
            return TransactionResponse.from(record.getTransaction());
        }
        throw new DuplicateTransferException(request.idempotencyKey());
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
        validateCurrency(fromAccount, toAccount, request.currency());
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

        tx.markCompleted(OffsetDateTime.now());
        transactionRepository.save(tx);
        transactionRepository.flush();

        ledgerService.recordTransfer(tx);
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

    private void validateCurrency(Account fromAccount, Account toAccount, String requestedCurrency) {
        if (!fromAccount.getCurrency().equals(toAccount.getCurrency())) {
            throw new CurrencyMismatchException(
                    "Cross-currency transfer is not supported: from="
                            + fromAccount.getCurrency()
                            + ", to="
                            + toAccount.getCurrency()
            );
        }

        if (requestedCurrency != null && !requestedCurrency.equals(fromAccount.getCurrency())) {
            throw new CurrencyMismatchException(
                    "Request currency does not match account currency: request="
                            + requestedCurrency
                            + ", account="
                            + fromAccount.getCurrency()
            );
        }
    }

    private void validateSufficientBalance(Account account, BigDecimal required) {
        if (account.getBalance().compareTo(required) < 0) {
            throw new InsufficientBalanceException(account.getBalance(), required);
        }
    }
}
