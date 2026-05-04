package com.actilazion.aries_transaction.dto.responses;

import com.actilazion.aries_transaction.entity.User;
import com.actilazion.aries_transaction.entity.enums.Role;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String fullName,
        String email,
        Role role,
        Boolean isActive,
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
                user.getCreatedAt()
        );
    }
}
