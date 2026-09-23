package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.account.domain.AccountStatus;
import com.actilazion.aries_transaction.account.infrastructure.AccountRepository;
import com.actilazion.aries_transaction.audit.application.AuditLogService;
import com.actilazion.aries_transaction.audit.domain.AuditEventType;
import com.actilazion.aries_transaction.common.exception.ForbiddenOperationException;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.ledger.application.LedgerService;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.payment.application.PaymentQrTransferGuard;
import com.actilazion.aries_transaction.payment.domain.PaymentQrCode;
import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecord;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionOperation;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.domain.TransferAmountPolicy;
import com.actilazion.aries_transaction.transaction.domain.TransferPreview;
import com.actilazion.aries_transaction.transaction.domain.exception.AccountNotActiveException;
import com.actilazion.aries_transaction.transaction.domain.exception.CurrencyMismatchException;
import com.actilazion.aries_transaction.transaction.domain.exception.IdempotencyConflictException;
import com.actilazion.aries_transaction.transaction.domain.exception.InsufficientBalanceException;
import com.actilazion.aries_transaction.transaction.domain.exception.SelfTransferException;
import com.actilazion.aries_transaction.transaction.domain.exception.TransferPreviewUnavailableException;
import com.actilazion.aries_transaction.transaction.dto.RefundRequest;
import com.actilazion.aries_transaction.transaction.dto.ReversalRequest;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.dto.TransferExecuteRequest;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.infrastructure.TransferPreviewRepository;
import com.actilazion.aries_transaction.transaction.infrastructure.TransactionRepository;
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
    private final TransferActorAccess actorAccess;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;
    private final OutboxEventService outboxEventService;
    private final LedgerService ledgerService;
    private final TransferPreviewRepository transferPreviewRepository;
    private final PaymentQrTransferGuard paymentQrTransferGuard;
    private final TransactionQueryService transactionQueryService;
    private final TransactionCompensationService compensationService;
    private final TransferAccountLocker accountLocker;
    private final com.actilazion.aries_transaction.smartotp.application.SmartOtpTransferGuard smartOtp;

    @Override
    @Transactional
    public TransactionResponse execute(TransferExecuteRequest request, String initiatorEmail) {
        User initiator = actorAccess.lockInitiator(initiatorEmail);
        var otpCredential = smartOtp.lockCredential(initiator);
        TransferPreview preview = transferPreviewRepository.findByIdWithLock(request.previewId())
                .orElseThrow(() -> new ResourceNotFoundException("Transfer preview", request.previewId()));
        if (!preview.getInitiator().getId().equals(initiator.getId())) {
            throw new ForbiddenOperationException("Caller is not authorized for this transfer preview");
        }
        TransferRequest transferRequest = new TransferRequest(
                preview.getSourceAccount().getId().toString(),
                preview.getDestinationAccount().getId().toString(),
                TransferAmountPolicy.normalize(preview.getAmount()), request.idempotencyKey(),
                preview.getCurrency(), preview.getDescription(), preview.getId());
        var existing = idempotencyService.findTransferRecord(transferRequest, initiatorEmail);
        if (existing.isPresent()) {
            if (!idempotencyService.matchesRequest(existing.get(), transferRequest)) {
                throw new IdempotencyConflictException(request.idempotencyKey());
            }
            return idempotencyService.responseFromPayload(existing.get(), request.idempotencyKey());
        }
        if (preview.getConsumedAt() != null) {
            throw new TransferPreviewUnavailableException(TransferPreviewUnavailableException.Reason.CONSUMED);
        }
        if (!preview.getExpiresAt().isAfter(OffsetDateTime.now())) {
            throw new TransferPreviewUnavailableException(TransferPreviewUnavailableException.Reason.EXPIRED);
        }
        smartOtp.authorize(initiator, otpCredential, preview, request.idempotencyKey(), request.authorizationId());
        PaymentQrCode qr = paymentQrTransferGuard.lockAndValidate(preview);
        TransactionResponse response = createTransfer(transferRequest, initiator, initiatorEmail);
        smartOtp.recheckDeadline(initiator.getId(), request.authorizationId());
        paymentQrTransferGuard.complete(qr, response.id(), initiator.getId());
        preview.setConsumedAt(OffsetDateTime.now());
        transferPreviewRepository.save(preview);
        return response;
    }

    @Override
    @Transactional
    public TransactionResponse transfer(TransferRequest request, String initiatorEmail) {
        if (request.previewId() != null) {
            throw new IllegalArgumentException("Preview-bound transfers must use execute");
        }
        TransferRequest normalizedRequest = normalizeTransferRequest(request);
        User initiator = actorAccess.lockInitiator(initiatorEmail);
        smartOtp.lockCredential(initiator);
        var existing = idempotencyService.findTransferRecord(normalizedRequest, initiatorEmail);
        if (existing.isPresent()) {
            return responseForIdempotentRetry(existing.get(), normalizedRequest);
        }

        return createTransfer(normalizedRequest, initiator, initiatorEmail);
    }

    private TransactionResponse createTransfer(
            TransferRequest request,
            User initiator,
            String initiatorEmail
    ) {
        IdempotencyRecord record = idempotencyService.createProcessingRecord(request, initiatorEmail);
        TransactionResponse response = doTransfer(request, initiator);
        completeIdempotencyRecord(record, response);
        return response;
    }

    private TransferRequest normalizeTransferRequest(TransferRequest request) {
        return new TransferRequest(
                request.fromAccountId(),
                request.toAccountId(),
                TransferAmountPolicy.normalize(request.amount()),
                request.idempotencyKey(),
                request.currency(),
                request.description(),
                request.previewId()
        );
    }

    @Override
    public TransactionResponse reverse(UUID originalTransactionId, ReversalRequest request, String initiatorEmail) {
        return compensationService.reverse(originalTransactionId, request, initiatorEmail);
    }

    @Override
    public TransactionResponse refund(UUID originalTransactionId, RefundRequest request, String initiatorEmail) {
        return compensationService.refund(originalTransactionId, request, initiatorEmail);
    }

    private TransactionResponse responseForIdempotentRetry(IdempotencyRecord record, TransferRequest request) {
        if (!idempotencyService.matchesRequest(record, request)) {
            throw new IdempotencyConflictException(request.idempotencyKey());
        }
        return responseForCompletedRetry(record, request.idempotencyKey());
    }

    private TransactionResponse responseForCompletedRetry(IdempotencyRecord record, String idempotencyKey) {
        return idempotencyService.responseFromPayload(record, idempotencyKey);
    }

    private void completeIdempotencyRecord(IdempotencyRecord record, TransactionResponse response) {
        Transaction tx = transactionRepository.getReferenceById(response.id());
        idempotencyService.markCompleted(record, tx, response);
    }

    private TransactionResponse doTransfer(TransferRequest request, User initiator) {
        UUID fromId = UUID.fromString(request.fromAccountId());
        UUID toId = UUID.fromString(request.toAccountId());

        if (fromId.equals(toId)) {
            throw new SelfTransferException("Self transfer is not allowed");
        }

        var accounts = accountLocker.lock(fromId, toId);
        Account fromAccount = accounts.fromAccount();
        Account toAccount = accounts.toAccount();

        actorAccess.assertOwnsAccount(fromAccount, initiator);
        if (request.previewId() == null) {
            smartOtp.legacy(fromAccount, toAccount);
        }
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
                .operation(TransactionOperation.TRANSFER)
                .idempotencyKey(request.idempotencyKey())
                .description(request.description())
                .status(TransactionStatus.PENDING)
                .build();
        transactionRepository.save(tx);
        auditLogService.log(tx, AuditEventType.TRANSFER_INITIATED, initiator.getEmail());

        fromAccount.setBalance(fromAccount.getBalance().subtract(request.amount()));
        toAccount.setBalance(toAccount.getBalance().add(request.amount()));

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        tx.markCompleted(OffsetDateTime.now());
        transactionRepository.save(tx);
        transactionRepository.flush();

        ledgerService.recordTransfer(tx);
        outboxEventService.recordTransferCompleted(tx);

        auditLogService.log(tx, AuditEventType.TRANSFER_COMPLETED, initiator.getEmail());
        log.info("[TRANSFER] COMPLETED txId={} from={} to={} amount={}", tx.getId(), fromId, toId, request.amount());

        return TransactionResponse.from(tx);
    }

    @Override
    public TransactionResponse getById(UUID txId, String requesterEmail) {
        return transactionQueryService.getById(txId, requesterEmail);
    }

    @Override
    public Page<TransactionResponse> getByAccount(UUID accountId, Pageable pageable, String requesterEmail) {
        return transactionQueryService.getByAccount(accountId, pageable, requesterEmail);
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
