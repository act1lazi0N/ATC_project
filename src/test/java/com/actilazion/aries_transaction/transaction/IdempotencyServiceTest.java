package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.transaction.application.IdempotencyService;
import com.actilazion.aries_transaction.transaction.domain.IdempotencyRecord;
import com.actilazion.aries_transaction.transaction.domain.exception.DuplicateTransferException;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.transaction.infrastructure.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {
    private static final String INITIATOR_EMAIL = "initiator@test.local";

    private final IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
    private final IdempotencyService service = new IdempotencyService(repository);

    @Test
    void createProcessingRecord_scopeCollision_mapsToDuplicateTransfer() {
        when(repository.saveAndFlush(any(IdempotencyRecord.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key violates constraint UK_IDEMPOTENCY_RECORDS_SCOPE"
                ));

        assertThatThrownBy(() -> service.createProcessingRecord(request(), INITIATOR_EMAIL))
                .isInstanceOf(DuplicateTransferException.class);
    }

    @Test
    void createProcessingRecord_otherIntegrityViolation_preservesOriginalFailure() {
        DataIntegrityViolationException violation =
                new DataIntegrityViolationException("idempotency_records_request_hash_not_null");
        when(repository.saveAndFlush(any(IdempotencyRecord.class))).thenThrow(violation);

        assertThatThrownBy(() -> service.createProcessingRecord(request(), INITIATOR_EMAIL))
                .isSameAs(violation);
    }

    private TransferRequest request() {
        return new TransferRequest(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                new BigDecimal("1000.00"),
                "idempotency-key-0001",
                "VND",
                "test transfer"
        );
    }
}
