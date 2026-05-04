package com.actilazion.aries_transaction.dto.responses;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public record AuthResponse(String accessToken, String refreshToken, String tokenType, Long userId, String email, String fullName, String role) {
   public static AuthResponse from(String accessToken, String refreshToken, String tokenType, Long userId, String email, String fullName, String role) {
       return new AuthResponse(accessToken, refreshToken, tokenType, userId, email, fullName, role);
   }
}
