package com.actilazion.aries_transaction.identity.api;

import com.actilazion.aries_transaction.identity.dto.LoginRequest;
import com.actilazion.aries_transaction.identity.dto.RegisterRequest;
import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.identity.dto.AuthResponse;
import com.actilazion.aries_transaction.identity.dto.UserResponse;
import com.actilazion.aries_transaction.identity.application.AuthService;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.config.JwtConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Authentication endpoints")
public class AuthController {
    private static final String REFRESH_COOKIE = "refresh_token";
    private final AuthService authService;
    private final JwtConfig jwtConfig;

    @PostMapping("/register")
    @Operation(summary = "Register a new user account")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        return withRefreshCookie(HttpStatus.CREATED, "User registered successfully", authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Login an receive a JWT token")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return withRefreshCookie(HttpStatus.OK, "Login successful", authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh session and issue a new access token")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken
    ) {
        return withRefreshCookie(HttpStatus.OK, "Token refreshed", authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current refresh session")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken
    ) {
        authService.logout(principal.getUserId(), refreshToken);
        return ResponseEntity.ok()
                .header("Set-Cookie", clearRefreshCookie().toString())
                .body(ApiResponse.ok("Logged out", null));
    }

    @GetMapping("/me")
    @Operation(summary = "Get the authenticated user")
    public ResponseEntity<ApiResponse<UserResponse>> me(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal
    ) {
        return ResponseEntity.ok(ApiResponse.ok("Current user", authService.me(principal.getUserId())));
    }

    private ResponseEntity<ApiResponse<AuthResponse>> withRefreshCookie(
            HttpStatus status, String message, AuthResponse response) {
        return ResponseEntity.status(status)
                .header("Set-Cookie", refreshCookie(response.refreshToken()).toString())
                .body(ApiResponse.ok(message, response));
    }

    private ResponseCookie refreshCookie(String token) {
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(jwtConfig.isRefreshCookieSecure())
                .sameSite(jwtConfig.getRefreshCookieSameSite())
                .path("/api/v1/auth")
                .maxAge(jwtConfig.getRefreshExpiration())
                .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(jwtConfig.isRefreshCookieSecure())
                .sameSite(jwtConfig.getRefreshCookieSameSite())
                .path("/api/v1/auth")
                .maxAge(0)
                .build();
    }
}
