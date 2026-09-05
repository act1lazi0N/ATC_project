package com.actilazion.aries_transaction.identity.dto;

import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.domain.Role;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String fullName,
        String email,
        Role role,
        Boolean isActive,
        boolean emailVerified,
        OffsetDateTime createdAt
)
{
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getIsActive(),
                user.getEmailVerifiedAt() != null,
                user.getCreatedAt()
        );
    }
}
