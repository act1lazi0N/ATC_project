package com.actilazion.aries_transaction.reconciliation.dto;

import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationException;
import com.actilazion.aries_transaction.reconciliation.domain.ReconciliationExceptionType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record ReconciliationExceptionResponse(
        UUID id,
        ReconciliationExceptionType exceptionType,
        UUID transactionId,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal sourceAmount,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal reportingAmount,
        String sourceStatus,
        String reportingStatus,
        String details,
        OffsetDateTime createdAt
) {
    public static ReconciliationExceptionResponse from(ReconciliationException exception) {
        return new ReconciliationExceptionResponse(
                exception.getId(),
                exception.getExceptionType(),
                exception.getTransactionId(),
                exception.getSourceAmount(),
                exception.getReportingAmount(),
                exception.getSourceStatus(),
                exception.getReportingStatus(),
                exception.getDetails(),
                exception.getCreatedAt()
        );
    }
}
