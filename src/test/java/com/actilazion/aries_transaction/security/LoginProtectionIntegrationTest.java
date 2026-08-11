package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.identity.application.AuthService;
import com.actilazion.aries_transaction.identity.dto.LoginRequest;
import com.actilazion.aries_transaction.identity.dto.RegisterRequest;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class LoginProtectionIntegrationTest {
    @Autowired
    AuthService authService;

    @Autowired
    UserRepository userRepository;

    @Test
    void repeatedFailures_temporarilyLockAccount_andCorrectPasswordStaysUnauthorized() {
        String email = "lock-" + System.nanoTime() + "@test.local";
        authService.register(new RegisterRequest("Lock Test User", email, "password-123456"));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(email, "wrong-password")))
                    .isInstanceOf(AuthenticationException.class);
        }

        var user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getFailedLoginAttempts()).isEqualTo(3);
        assertThat(user.getLockedUntil()).isNotNull();
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "password-123456")))
                .hasMessage("Unauthorized");
    }
}
