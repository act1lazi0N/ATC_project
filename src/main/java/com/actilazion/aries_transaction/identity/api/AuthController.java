package com.actilazion.aries_transaction.identity.api;

import com.actilazion.aries_transaction.identity.dto.LoginRequest;
import com.actilazion.aries_transaction.identity.dto.RegisterRequest;
import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.identity.dto.AuthResponse;
import com.actilazion.aries_transaction.identity.dto.UserResponse;
import com.actilazion.aries_transaction.identity.application.AuthService;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.config.JwtConfig;
import com.actilazion.aries_transaction.config.AuthRateLimiter;
import com.actilazion.aries_transaction.config.RefreshCookiePolicy;
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
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Authentication endpoints")
public class AuthController {
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String NO_STORE = "no-store";
    private final AuthService authService;
    private final JwtConfig jwtConfig;
    private final AuthRateLimiter authRateLimiter;
    private final RefreshCookiePolicy refreshCookiePolicy;

    @PostMapping("/register")
    @Operation(summary = "Register a new user account")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest
    ) {
        authRateLimiter.check("register", httpRequest, request.email());
        return withRefreshCookie(HttpStatus.CREATED, "User registered successfully",
                authService.register(request, httpRequest.getRemoteAddr()));
    }

    @PostMapping("/login")
    @Operation(summary = "Login an receive a JWT token")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        authRateLimiter.check("login", httpRequest, request.email());
        return withRefreshCookie(HttpStatus.OK, "Login successful",
                authService.login(request, httpRequest.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh session and issue a new access token")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest httpRequest
    ) {
        refreshCookiePolicy.enforce(httpRequest);
        authRateLimiter.check("refresh", httpRequest, refreshToken);
        return withRefreshCookie(HttpStatus.OK, "Token refreshed",
                authService.refresh(refreshToken, httpRequest.getRemoteAddr()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current refresh session")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest httpRequest,
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken
    ) {
        refreshCookiePolicy.enforce(httpRequest);
        authService.logout(refreshToken);
        return ResponseEntity.ok()
                .header("Cache-Control", NO_STORE)
                .header("Set-Cookie", clearRefreshCookie().toString())
                .body(ApiResponse.ok("Logged out", null));
    }

    @GetMapping("/me")
    @Operation(summary = "Get the authenticated user")
    public ResponseEntity<ApiResponse<UserResponse>> me(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal
    ) {
        return ResponseEntity.ok()
                .header("Cache-Control", NO_STORE)
                .body(ApiResponse.ok("Current user", authService.me(principal.getUserId())));
    }

    private ResponseEntity<ApiResponse<AuthResponse>> withRefreshCookie(
            HttpStatus status, String message, AuthResponse response) {
        return ResponseEntity.status(status)
                .header("Cache-Control", NO_STORE)
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
