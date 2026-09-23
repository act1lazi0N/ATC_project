package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.audit.domain.AuditEventType;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.ledger.application.LedgerService;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecord;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionOperation;
import com.actilazion.aries_transaction.transaction.domain.TransactionStateGuard;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.domain.exception.AccountNotActiveException;
import com.actilazion.aries_transaction.transaction.domain.exception.InsufficientBalanceException;
import com.actilazion.aries_transaction.transaction.domain.exception.IdempotencyConflictException;
import com.actilazion.aries_transaction.transaction.domain.exception.RefundAmountExceededException;
import com.actilazion.aries_transaction.transaction.dto.RefundRequest;
import com.actilazion.aries_transaction.transaction.dto.ReversalRequest;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionCompensationService {
    private final TransferAccountLocker accountLocker;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final OutboxEventService outboxEventService;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;
    private final TransferActorAccess actorAccess;

    @Transactional
    public TransactionResponse reverse(UUID originalTransactionId, ReversalRequest request, String initiatorEmail) {
        User initiator = actorAccess.lockInitiator(initiatorEmail);
        Transaction original = lockTransaction(originalTransactionId);
        assertCanReverse(initiator);
        var existing = idempotencyService.findReversalRecord(request, initiatorEmail);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!idempotencyService.matchesRequest(record, request, original)) {
                throw new IdempotencyConflictException(request.idempotencyKey());
            }
            return idempotencyService.responseFromPayload(record, request.idempotencyKey());
        }
        IdempotencyRecord record = idempotencyService.createProcessingRecord(request, original, initiatorEmail);
        TransactionResponse response = doReverse(original, request, initiator);
        completeIdempotencyRecord(record, response);
        return response;
    }

    @Transactional
    public TransactionResponse refund(UUID originalTransactionId, RefundRequest request, String initiatorEmail) {
        User initiator = actorAccess.lockInitiator(initiatorEmail);
        Transaction original = lockTransaction(originalTransactionId);
        assertCanRefund(original, initiator);
        var existing = idempotencyService.findRefundRecord(request, initiatorEmail);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!idempotencyService.matchesRequest(record, request, original)) {
                throw new IdempotencyConflictException(request.idempotencyKey());
            }
            return idempotencyService.responseFromPayload(record, request.idempotencyKey());
        }
        IdempotencyRecord record = idempotencyService.createProcessingRecord(request, original, initiatorEmail);
        TransactionResponse response = doRefund(original, request, initiator);
        completeIdempotencyRecord(record, response);
        return response;
    }

    private TransactionResponse doReverse(Transaction original, ReversalRequest request, User initiator) {
        TransactionStateGuard.assertCanReverse(original);
        var accounts = accountLocker.lock(original.getToAccount().getId(), original.getFromAccount().getId());
        Account fromAccount = accounts.fromAccount();
        Account toAccount = accounts.toAccount();
        Transaction tx = createCompensatingTransaction(original, fromAccount, toAccount, original.getAmount(),
                TransactionOperation.REVERSAL, request.idempotencyKey(), request.description(), initiator);

        moveBalance(fromAccount, toAccount, original.getAmount());
        original.markReversed();
        transactionRepository.save(original);
        tx.markCompleted(OffsetDateTime.now());
        transactionRepository.save(tx);
        transactionRepository.flush();

        ledgerService.recordReversal(tx);
        outboxEventService.recordReversalCompleted(tx);
        auditLogService.log(original, AuditEventType.TRANSFER_REVERSED, initiator.getEmail());
        auditLogService.log(tx, AuditEventType.TRANSFER_COMPLETED, initiator.getEmail());
        return TransactionResponse.from(tx);
    }

    private TransactionResponse doRefund(Transaction original, RefundRequest request, User initiator) {
        TransactionStateGuard.assertCanRefund(original);
        BigDecimal alreadyRefunded = original.getRefundedAmount() != null
                ? original.getRefundedAmount() : BigDecimal.ZERO;
        BigDecimal remaining = original.getAmount().subtract(alreadyRefunded);
        if (request.amount().compareTo(remaining) > 0) {
            throw new RefundAmountExceededException(request.amount(), remaining);
        }
        var accounts = accountLocker.lock(original.getToAccount().getId(), original.getFromAccount().getId());
        Account fromAccount = accounts.fromAccount();
        Account toAccount = accounts.toAccount();
        Transaction tx = createCompensatingTransaction(original, fromAccount, toAccount, request.amount(),
                TransactionOperation.REFUND, request.idempotencyKey(), request.description(), initiator);

        moveBalance(fromAccount, toAccount, request.amount());
        BigDecimal refundedAmount = alreadyRefunded.add(request.amount());
        original.setRefundedAmount(refundedAmount);
        if (refundedAmount.compareTo(original.getAmount()) == 0) {
            original.markRefunded();
        } else {
            original.markPartiallyRefunded();
        }
        transactionRepository.save(original);
        tx.markCompleted(OffsetDateTime.now());
        transactionRepository.save(tx);
        transactionRepository.flush();

        ledgerService.recordRefund(tx);
        outboxEventService.recordRefundCompleted(tx);
        auditLogService.log(original, AuditEventType.TRANSFER_REFUNDED, initiator.getEmail());
        auditLogService.log(tx, AuditEventType.TRANSFER_COMPLETED, initiator.getEmail());
        return TransactionResponse.from(tx);
    }

    private Transaction createCompensatingTransaction(Transaction original, Account fromAccount, Account toAccount,
                                                      BigDecimal amount, TransactionOperation operation,
                                                      String idempotencyKey, String description, User initiator) {
        validateAccountActive(fromAccount);
        validateAccountActive(toAccount);
        validateSufficientBalance(fromAccount, amount);
        return Transaction.builder()
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .initiatedBy(initiator)
                .amount(amount)
                .currency(original.getCurrency())
                .operation(operation)
                .idempotencyKey(idempotencyKey)
                .description(description)
                .originalTransaction(original)
                .status(TransactionStatus.PENDING)
                .build();
    }

    private void completeIdempotencyRecord(IdempotencyRecord record, TransactionResponse response) {
        Transaction tx = transactionRepository.getReferenceById(response.id());
        idempotencyService.markCompleted(record, tx, response);
    }

    private Transaction lockTransaction(UUID transactionId) {
        return transactionRepository.findByIdWithLock(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionId));
    }

    private void assertCanReverse(User initiator) {
        if (initiator.getRole() != Role.ADMIN && initiator.getRole() != Role.OPERATOR) {
            throw new AccessDeniedException("Caller is not authorized to reverse transactions");
        }
    }

    private void assertCanRefund(Transaction original, User initiator) {
        if (initiator.getRole() == Role.OPERATOR) {
            return;
        }
        if (initiator.getRole() == Role.MERCHANT) {
            actorAccess.assertOwnsAccount(original.getToAccount(), initiator);
            return;
        }
        throw new AccessDeniedException("Caller is not authorized to refund transactions");
    }

    private void moveBalance(Account fromAccount, Account toAccount, BigDecimal amount) {
        fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
        toAccount.setBalance(toAccount.getBalance().add(amount));
        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);
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
