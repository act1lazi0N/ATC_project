package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.identity.application.AuthService;
import com.actilazion.aries_transaction.identity.dto.AuthResponse;
import com.actilazion.aries_transaction.identity.dto.RegisterRequest;
import com.actilazion.aries_transaction.identity.infrastructure.RefreshSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class RefreshSessionIntegrationTest {
    @Autowired
    AuthService authService;

    @Autowired
    RefreshSessionRepository refreshSessionRepository;

    @Test
    void refresh_rotatesToken_andOldTokenIsRejected() {
        AuthResponse initial = register();

        AuthResponse rotated = authService.refresh(initial.refreshToken());

        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(rotated.accessToken()).isNotBlank();
        assertThat(refreshSessionRepository.countByUserId(initial.user().id())).isEqualTo(2);
        assertThatThrownBy(() -> authService.refresh(initial.refreshToken()))
                .hasMessage("Unauthorized");
    }

    @Test
    void logout_revokesCurrentRefreshSession() {
        AuthResponse initial = register();

        authService.logout(initial.user().id(), initial.refreshToken());

        assertThatThrownBy(() -> authService.refresh(initial.refreshToken()))
                .hasMessage("Unauthorized");
    }

    private AuthResponse register() {
        return authService.register(new RegisterRequest(
                "Refresh Test User",
                "refresh-" + System.nanoTime() + "@test.local",
                "password-123456"));
    }
}
