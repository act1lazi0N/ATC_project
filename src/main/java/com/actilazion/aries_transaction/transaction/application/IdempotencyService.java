package com.actilazion.aries_transaction.transaction.application;

import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecord;
import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecordStatus;
import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.persistence.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
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
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public Optional<IdempotencyRecord> findByKey(String idempotencyKey) {
        return idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
    }

    public IdempotencyRecord createProcessingRecord(TransferRequest request) {
        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey(request.idempotencyKey())
                .requestHash(hash(request))
                .status(IdempotencyRecordStatus.PROCESSING)
                .build();
        return idempotencyRecordRepository.saveAndFlush(record);
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

    public String hash(TransferRequest request) {
        String canonical = String.join("|",
                request.fromAccountId(),
                request.toAccountId(),
                request.amount().stripTrailingZeros().toPlainString(),
                request.currency() != null ? request.currency() : "",
                request.description() != null ? request.description() : ""
        );
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
        payload.put("createdAt", response.createdAt() != null ? response.createdAt().toString() : null);
        payload.put("completedAt", response.completedAt() != null ? response.completedAt().toString() : null);
        return payload;
    }
}
