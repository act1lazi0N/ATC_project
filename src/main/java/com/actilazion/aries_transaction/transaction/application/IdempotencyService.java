package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecord;
import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecordStatus;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionOperation;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.domain.exception.DuplicateTransferException;
import com.actilazion.aries_transaction.transaction.dto.RefundRequest;
import com.actilazion.aries_transaction.transaction.dto.ReversalRequest;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.infrastructure.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String TRANSFER_OPERATION = TransactionOperation.TRANSFER.name();
    private static final String REVERSAL_OPERATION = TransactionOperation.REVERSAL.name();
    private static final String REFUND_OPERATION = TransactionOperation.REFUND.name();
    private static final String IDEMPOTENCY_SCOPE_CONSTRAINT = "uk_idempotency_records_scope";

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public Optional<IdempotencyRecord> findTransferRecord(TransferRequest request, String initiatorEmail) {
        return findByScope(request.idempotencyKey(), TRANSFER_OPERATION, initiatorEmail);
    }

    public Optional<IdempotencyRecord> findReversalRecord(ReversalRequest request, String initiatorEmail) {
        return findByScope(request.idempotencyKey(), REVERSAL_OPERATION, initiatorEmail);
    }

    public Optional<IdempotencyRecord> findRefundRecord(RefundRequest request, String initiatorEmail) {
        return findByScope(request.idempotencyKey(), REFUND_OPERATION, initiatorEmail);
    }

    private Optional<IdempotencyRecord> findByScope(String idempotencyKey, String operation, String initiatorEmail) {
        return idempotencyRecordRepository.findByIdempotencyKeyAndOperationAndInitiatorEmail(
                idempotencyKey,
                operation,
                initiatorEmail
        );
    }

    public IdempotencyRecord createProcessingRecord(TransferRequest request, String initiatorEmail) {
        return createProcessingRecord(request.idempotencyKey(), TRANSFER_OPERATION, initiatorEmail, hash(request));
    }

    public IdempotencyRecord createProcessingRecord(
            ReversalRequest request,
            Transaction originalTransaction,
            String initiatorEmail
    ) {
        return createProcessingRecord(
                request.idempotencyKey(),
                REVERSAL_OPERATION,
                initiatorEmail,
                hash(request, originalTransaction)
        );
    }

    public IdempotencyRecord createProcessingRecord(
            RefundRequest request,
            Transaction originalTransaction,
            String initiatorEmail
    ) {
        return createProcessingRecord(
                request.idempotencyKey(),
                REFUND_OPERATION,
                initiatorEmail,
                hash(request, originalTransaction)
        );
    }

    private IdempotencyRecord createProcessingRecord(
            String idempotencyKey,
            String operation,
            String initiatorEmail,
            String requestHash
    ) {
        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey(idempotencyKey)
                .operation(operation)
                .initiatorEmail(initiatorEmail)
                .requestHash(requestHash)
                .status(IdempotencyRecordStatus.PROCESSING)
                .build();
        try {
            return idempotencyRecordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            if (isIdempotencyScopeCollision(ex)) {
                throw new DuplicateTransferException(idempotencyKey);
            }
            throw ex;
        }
    }

    private boolean isIdempotencyScopeCollision(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains(IDEMPOTENCY_SCOPE_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    public void markCompleted(IdempotencyRecord record, Transaction transaction, TransactionResponse response) {
        record.setTransaction(transaction);
        record.setResponsePayload(toPayload(response));
        record.setStatus(IdempotencyRecordStatus.COMPLETED);
        record.setCompletedAt(OffsetDateTime.now());
        idempotencyRecordRepository.save(record);
    }

    public TransactionResponse responseFromPayload(IdempotencyRecord record, String idempotencyKey) {
        if (record.getStatus() != IdempotencyRecordStatus.COMPLETED || record.getResponsePayload() == null) {
            throw new DuplicateTransferException(idempotencyKey);
        }
        Map<String, Object> payload = record.getResponsePayload();
        return new TransactionResponse(
                UUID.fromString((String) payload.get("id")),
                UUID.fromString((String) payload.get("fromAccountId")),
                UUID.fromString((String) payload.get("toAccountId")),
                new BigDecimal((String) payload.get("amount")),
                (String) payload.get("currency"),
                TransactionStatus.valueOf((String) payload.get("status")),
                (String) payload.get("idempotencyKey"),
                (String) payload.get("description"),
                (String) payload.get("failureReason"),
                payload.get("originalTransactionId") == null
                        ? null
                        : UUID.fromString((String) payload.get("originalTransactionId")),
                payload.get("refundedAmount") == null
                        ? null
                        : new BigDecimal((String) payload.get("refundedAmount")),
                payload.get("createdAt") == null
                        ? null
                        : OffsetDateTime.parse((String) payload.get("createdAt")),
                payload.get("completedAt") == null
                        ? null
                        : OffsetDateTime.parse((String) payload.get("completedAt"))
        );
    }

    public boolean matchesRequest(IdempotencyRecord record, TransferRequest request) {
        return record.getRequestHash().equals(hash(request));
    }

    public boolean matchesRequest(IdempotencyRecord record, ReversalRequest request, Transaction originalTransaction) {
        return record.getRequestHash().equals(hash(request, originalTransaction));
    }

    public boolean matchesRequest(IdempotencyRecord record, RefundRequest request, Transaction originalTransaction) {
        return record.getRequestHash().equals(hash(request, originalTransaction));
    }

    public String hash(TransferRequest request) {
        return hashParts(
                "TRANSFER",
                request.fromAccountId(),
                request.toAccountId(),
                request.amount().stripTrailingZeros().toPlainString(),
                request.currency() != null ? request.currency() : "",
                request.description() != null ? request.description() : ""
        );
    }

    public String hash(ReversalRequest request, Transaction originalTransaction) {
        return hashParts(
                "REVERSAL",
                originalTransaction.getId().toString(),
                originalTransaction.getAmount().stripTrailingZeros().toPlainString(),
                request.description() != null ? request.description() : ""
        );
    }

    public String hash(RefundRequest request, Transaction originalTransaction) {
        return hashParts(
                "REFUND",
                originalTransaction.getId().toString(),
                request.amount().stripTrailingZeros().toPlainString(),
                request.description() != null ? request.description() : ""
        );
    }

    private String hashParts(String... parts) {
        String canonical = String.join("|", parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private Map<String, Object> toPayload(TransactionResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", response.id().toString());
        payload.put("fromAccountId", response.fromAccountId().toString());
        payload.put("toAccountId", response.toAccountId().toString());
        payload.put("amount", response.amount().toPlainString());
        payload.put("currency", response.currency());
        payload.put("status", response.status().name());
        payload.put("idempotencyKey", response.idempotencyKey());
        payload.put("description", response.description());
        payload.put("failureReason", response.failureReason());
        payload.put(
                "originalTransactionId",
                response.originalTransactionId() != null ? response.originalTransactionId().toString() : null
        );
        payload.put(
                "refundedAmount",
                response.refundedAmount() != null ? response.refundedAmount().toPlainString() : null
        );
        payload.put("createdAt", response.createdAt() != null ? response.createdAt().toString() : null);
        payload.put("completedAt", response.completedAt() != null ? response.completedAt().toString() : null);
        return payload;
    }
}
