package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.identity.dto.AuthResponse;
import com.actilazion.aries_transaction.identity.dto.LoginRequest;
import com.actilazion.aries_transaction.identity.dto.RegisterRequest;
import com.actilazion.aries_transaction.identity.dto.UserResponse;

import java.util.UUID;

public interface AuthService {
    AuthResponse register(RegisterRequest request);

    default AuthResponse register(RegisterRequest request, String ipAddress) {
        return register(request);
    }

    AuthResponse login(LoginRequest request);

    default AuthResponse login(LoginRequest request, String ipAddress) {
        return login(request);
    }

    AuthResponse refresh(String refreshToken);

    default AuthResponse refresh(String refreshToken, String ipAddress) {
        return refresh(refreshToken);
    }

    void logout(UUID userId, String refreshToken);

    UserResponse me(UUID userId);
}
