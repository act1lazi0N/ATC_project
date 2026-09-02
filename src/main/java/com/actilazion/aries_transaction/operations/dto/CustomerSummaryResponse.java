package com.actilazion.aries_transaction.operations.dto;

import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CustomerSummaryResponse(
        UUID id,
        String fullName,
        String email,
        Role role,
        CustomerStatus status,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static CustomerSummaryResponse from(User user) {
        return new CustomerSummaryResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                Boolean.TRUE.equals(user.getIsActive()) ? CustomerStatus.ACTIVE : CustomerStatus.SUSPENDED,
                user.getVersion(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
