package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.dto.RefundRequest;
import com.actilazion.aries_transaction.transaction.dto.ReversalRequest;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.audit.domain.AuditEventType;
import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecord;
import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecordStatus;
import com.actilazion.aries_transaction.transaction.domain.TransactionStateGuard;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.domain.exception.AccountNotActiveException;
import com.actilazion.aries_transaction.transaction.domain.exception.CurrencyMismatchException;
import com.actilazion.aries_transaction.transaction.domain.exception.DuplicateTransferException;
import com.actilazion.aries_transaction.transaction.domain.exception.IdempotencyConflictException;
import com.actilazion.aries_transaction.transaction.domain.exception.InsufficientBalanceException;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.transaction.domain.exception.RefundAmountExceededException;
import com.actilazion.aries_transaction.transaction.domain.exception.SelfTransferException;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.transaction.application.IdempotencyService;
import com.actilazion.aries_transaction.ledger.application.LedgerService;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.transaction.application.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
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

    @Override
    @Transactional
    public TransactionResponse reverse(UUID originalTransactionId, ReversalRequest request, String initiatorEmail) {
        Transaction original = lockTransaction(originalTransactionId);
        User initiator = loadInitiator(initiatorEmail);
        assertCanReverse(initiator);
        var existing = idempotencyService.findByKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return responseForIdempotentRetry(existing.get(), request, original);
        }

        IdempotencyRecord record = idempotencyService.createProcessingRecord(request, original);
        TransactionResponse response = doReverse(original, request, initiator);
        Transaction tx = transactionRepository.getReferenceById(response.id());
        idempotencyService.markCompleted(record, tx, response);
        return response;
    }

    @Override
    @Transactional
    public TransactionResponse refund(UUID originalTransactionId, RefundRequest request, String initiatorEmail) {
        Transaction original = lockTransaction(originalTransactionId);
        User initiator = loadInitiator(initiatorEmail);
        assertCanRefund(original, initiator);
        var existing = idempotencyService.findByKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return responseForIdempotentRetry(existing.get(), request, original);
        }

        IdempotencyRecord record = idempotencyService.createProcessingRecord(request, original);
        TransactionResponse response = doRefund(original, request, initiator);
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

    private TransactionResponse responseForIdempotentRetry(
            IdempotencyRecord record,
            ReversalRequest request,
            Transaction original
    ) {
        if (!idempotencyService.matchesRequest(record, request, original)) {
            throw new IdempotencyConflictException(request.idempotencyKey());
        }

        if (record.getStatus() == IdempotencyRecordStatus.COMPLETED && record.getTransaction() != null) {
            return TransactionResponse.from(record.getTransaction());
        }
        throw new DuplicateTransferException(request.idempotencyKey());
    }

    private TransactionResponse responseForIdempotentRetry(
            IdempotencyRecord record,
            RefundRequest request,
            Transaction original
    ) {
        if (!idempotencyService.matchesRequest(record, request, original)) {
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

        User initiator = loadInitiator(initiatorEmail);
        assertOwnsAccount(fromAccount, initiator);
        validateAccountActive(fromAccount);
        validateAccountActive(toAccount);
        validateCurrency(fromAccount, toAccount, request.currency());
        validateSufficientBalance(fromAccount, request.amount());

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

    private TransactionResponse doReverse(Transaction original, ReversalRequest request, User initiator) {
        TransactionStateGuard.assertCanReverse(original);

        Account fromAccount = original.getToAccount();
        Account toAccount = original.getFromAccount();
        Account lockedFromAccount;
        Account lockedToAccount;
        if (fromAccount.getId().compareTo(toAccount.getId()) < 0) {
            lockedFromAccount = lockAccount(fromAccount.getId());
            lockedToAccount = lockAccount(toAccount.getId());
        } else {
            lockedToAccount = lockAccount(toAccount.getId());
            lockedFromAccount = lockAccount(fromAccount.getId());
        }

        Transaction tx = createCompensatingTransaction(
                original,
                lockedFromAccount,
                lockedToAccount,
                original.getAmount(),
                request.idempotencyKey(),
                request.description(),
                initiator
        );

        moveBalance(lockedFromAccount, lockedToAccount, original.getAmount());
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
                ? original.getRefundedAmount()
                : BigDecimal.ZERO;
        BigDecimal remaining = original.getAmount().subtract(alreadyRefunded);
        if (request.amount().compareTo(remaining) > 0) {
            throw new RefundAmountExceededException(request.amount(), remaining);
        }

        Account fromAccount = original.getToAccount();
        Account toAccount = original.getFromAccount();
        Account lockedFromAccount;
        Account lockedToAccount;
        if (fromAccount.getId().compareTo(toAccount.getId()) < 0) {
            lockedFromAccount = lockAccount(fromAccount.getId());
            lockedToAccount = lockAccount(toAccount.getId());
        } else {
            lockedToAccount = lockAccount(toAccount.getId());
            lockedFromAccount = lockAccount(fromAccount.getId());
        }

        Transaction tx = createCompensatingTransaction(
                original,
                lockedFromAccount,
                lockedToAccount,
                request.amount(),
                request.idempotencyKey(),
                request.description(),
                initiator
        );

        moveBalance(lockedFromAccount, lockedToAccount, request.amount());
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

    private Transaction createCompensatingTransaction(
            Transaction original,
            Account fromAccount,
            Account toAccount,
            BigDecimal amount,
            String idempotencyKey,
            String description,
            User initiator
    ) {
        validateAccountActive(fromAccount);
        validateAccountActive(toAccount);
        validateSufficientBalance(fromAccount, amount);

        return Transaction.builder()
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .initiatedBy(initiator)
                .amount(amount)
                .currency(original.getCurrency())
                .idempotencyKey(idempotencyKey)
                .description(description)
                .originalTransaction(original)
                .status(TransactionStatus.PENDING)
                .build();
    }

    private User loadInitiator(String initiatorEmail) {
        return userRepository.findByEmail(initiatorEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", initiatorEmail));
    }

    private void assertOwnsAccount(Account account, User initiator) {
        if (!account.getUser().getId().equals(initiator.getId())) {
            throw new AccessDeniedException("Caller is not authorized for this account");
        }
    }

    private void assertOwnsOriginalSourceAccount(Transaction original, User initiator) {
        assertOwnsAccount(original.getFromAccount(), initiator);
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
            assertOwnsOriginalSourceAccount(original, initiator);
            return;
        }
        throw new AccessDeniedException("Caller is not authorized to refund transactions");
    }

    private void assertCanViewTransaction(Transaction transaction, User viewer) {
        if (isPrivileged(viewer)) {
            return;
        }
        if (isAccountOwner(transaction.getFromAccount(), viewer) || isAccountOwner(transaction.getToAccount(), viewer)) {
            return;
        }
        throw new AccessDeniedException("Caller is not authorized to view this transaction");
    }

    private void assertCanViewAccount(Account account, User viewer) {
        if (isPrivileged(viewer)) {
            return;
        }
        assertOwnsAccount(account, viewer);
    }

    private boolean isPrivileged(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.OPERATOR;
    }

    private boolean isAccountOwner(Account account, User user) {
        return account.getUser().getId().equals(user.getId());
    }

    private void moveBalance(Account fromAccount, Account toAccount, BigDecimal amount) {
        fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
        toAccount.setBalance(toAccount.getBalance().add(amount));
        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionResponse getById(UUID txId, String viewerEmail) {
        Transaction tx = transactionRepository.findById(txId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", txId));
        User viewer = loadInitiator(viewerEmail);
        assertCanViewTransaction(tx, viewer);
        return TransactionResponse.from(tx);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransactionResponse> getByAccount(UUID accountId, Pageable pageable, String viewerEmail) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        User viewer = loadInitiator(viewerEmail);
        assertCanViewAccount(account, viewer);
        return transactionRepository
                .findAllByAccountId(accountId, pageable)
                .map(TransactionResponse::from);
    }

    private Account lockAccount(UUID accountId) {
        return accountRepository.findByIdWithLock(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    private Transaction lockTransaction(UUID transactionId) {
        return transactionRepository.findByIdWithLock(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionId));
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
