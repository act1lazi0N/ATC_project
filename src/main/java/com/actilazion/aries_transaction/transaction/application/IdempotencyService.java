package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecord;
import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecordStatus;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.dto.RefundRequest;
import com.actilazion.aries_transaction.transaction.dto.ReversalRequest;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.domain.exception.DuplicateTransferException;
import com.actilazion.aries_transaction.transaction.infrastructure.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    //TODO: Group status become Enums
    private static final String TRANSFER_OPERATION = "TRANSFER";
    private static final String REVERSAL_OPERATION = "REVERSAL";
    private static final String REFUND_OPERATION = "REFUND";

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public Optional<IdempotencyRecord> findByKey(String idempotencyKey) {
        return idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
    }

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
            throw new DuplicateTransferException(idempotencyKey);
        }
    }

    public void markCompleted(IdempotencyRecord record, Transaction transaction, TransactionResponse response) {
        record.setTransaction(transaction);
        record.setResponsePayload(toPayload(response));
        record.setStatus(IdempotencyRecordStatus.COMPLETED);
        record.setCompletedAt(OffsetDateTime.now());
        idempotencyRecordRepository.save(record);
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
            StringBuilder hash = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hash.append(String.format("%02x", b));
            }
            return hash.toString();
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
        payload.put("originalTransactionId", response.originalTransactionId() != null ? response.originalTransactionId().toString() : null);
        payload.put("refundedAmount", response.refundedAmount() != null ? response.refundedAmount().toPlainString() : null);
        payload.put("createdAt", response.createdAt() != null ? response.createdAt().toString() : null);
        payload.put("completedAt", response.completedAt() != null ? response.completedAt().toString() : null);
        return payload;
    }
}
