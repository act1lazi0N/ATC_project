package com.actilazion.aries_transaction.security;

import com.actilazion.aries_transaction.identity.application.AuthService;
import com.actilazion.aries_transaction.identity.dto.AuthResponse;
import com.actilazion.aries_transaction.identity.dto.LoginRequest;
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
        assertThatThrownBy(() -> authService.refresh(rotated.refreshToken()))
                .hasMessage("Unauthorized");
    }

    @Test
    void logout_revokesTheWholeRefreshFamily() {
        AuthResponse initial = register();
        AuthResponse rotated = authService.refresh(initial.refreshToken());

        authService.logout(initial.refreshToken());

        assertThatThrownBy(() -> authService.refresh(initial.refreshToken()))
                .hasMessage("Unauthorized");
        assertThatThrownBy(() -> authService.refresh(rotated.refreshToken()))
                .hasMessage("Unauthorized");
    }

    @Test
    void replayedRotatedToken_doesNotRevokeAnIndependentFamily() {
        RegisterRequest request = new RegisterRequest(
                "Refresh Family User",
                "refresh-family-" + System.nanoTime() + "@test.local",
                "password-123456");
        AuthResponse firstFamily = authService.register(request);
        AuthResponse secondFamily = authService.login(new LoginRequest(request.email(), request.password()));

        AuthResponse firstRotated = authService.refresh(firstFamily.refreshToken());
        assertThatThrownBy(() -> authService.refresh(firstFamily.refreshToken()))
                .hasMessage("Unauthorized");
        assertThatThrownBy(() -> authService.refresh(firstRotated.refreshToken()))
                .hasMessage("Unauthorized");

        assertThat(authService.refresh(secondFamily.refreshToken()).refreshToken())
                .isNotBlank();
    }

    @Test
    void lateRefreshAfterLogout_doesNotRevokeTheNextLoginFamily() {
        RegisterRequest request = new RegisterRequest(
                "Refresh Re-login User",
                "refresh-relogin-" + System.nanoTime() + "@test.local",
                "password-123456");
        AuthResponse firstLogin = authService.register(request);

        authService.logout(firstLogin.refreshToken());
        AuthResponse secondLogin = authService.login(new LoginRequest(request.email(), request.password()));

        assertThatThrownBy(() -> authService.refresh(firstLogin.refreshToken()))
                .hasMessage("Unauthorized");
        assertThat(authService.refresh(secondLogin.refreshToken()).refreshToken())
                .isNotBlank();
    }

    private AuthResponse register() {
        return authService.register(new RegisterRequest(
                "Refresh Test User",
                "refresh-" + System.nanoTime() + "@test.local",
                "password-123456"));
    }
}
