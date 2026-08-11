package com.actilazion.aries_transaction.identity.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserResponse user,
        @JsonIgnore String refreshToken
    ) {
    public static AuthResponse of(String token, long expiresIn, UserResponse user) {
        return new AuthResponse(token, "Bearer", expiresIn, user, null);
    }

    public static AuthResponse withRefresh(String token, long expiresIn, UserResponse user, String refreshToken) {
        return new AuthResponse(token, "Bearer", expiresIn, user, refreshToken);
    }
}
