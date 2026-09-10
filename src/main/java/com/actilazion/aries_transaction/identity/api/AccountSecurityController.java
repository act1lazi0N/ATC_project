package com.actilazion.aries_transaction.identity.api;

import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.common.redis.SecurityKeyHasher;
import com.actilazion.aries_transaction.config.AuthRateLimiter;
import com.actilazion.aries_transaction.config.ClientIpResolver;
import com.actilazion.aries_transaction.config.JwtConfig;
import com.actilazion.aries_transaction.config.RefreshCookiePolicy;
import com.actilazion.aries_transaction.identity.application.*;
import com.actilazion.aries_transaction.identity.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Account security")
public class AccountSecurityController {
    private final PasswordService passwords;
    private final PasswordRequestProtection protection;
    private final AccountSecurityProperties properties;
    private final AuthRateLimiter limiter;
    private final SecurityKeyHasher hasher;
    private final ClientIpResolver clientIpResolver;
    private final JwtConfig jwtConfig;
    private final RefreshCookiePolicy cookiePolicy;

    @PostMapping("/forgot-password")
    @Operation(summary = "Request a one-time password reset email",
            description = "Always returns the same accepted response for eligible, unknown and inactive identities. Acceptance does not confirm email delivery.",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Request accepted"))
    public ResponseEntity<ApiResponse<Void>> forgot(@Valid @RequestBody ForgotPasswordRequest body,
                                                   HttpServletRequest request) {
        protection.forgot(request, body.email(), () -> passwords.requestReset(body.email(), clientIpResolver.resolve(request)));
        return ResponseEntity.accepted().header("Cache-Control", "no-store")
                .body(ApiResponse.ok("If an eligible account exists, password reset instructions will be emailed.", null));
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change password and revoke every session, including the current session")
    public ResponseEntity<ApiResponse<Void>> change(@AuthenticationPrincipal AuthenticatedUserPrincipal principal,
                                                    @Valid @RequestBody ChangePasswordRequest body,
                                                    HttpServletRequest request) {
        properties.requireEnabled();
        cookiePolicy.enforce(request);
        limiter.check("change-password", request, principal.getUserId().toString());
        passwords.changePassword(principal, body.currentPassword(), body.newPassword(), clientIpResolver.resolve(request));
        return loggedOut("Password changed. Sign in again.");
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Consume a reset token, replace the password and revoke every session")
    public ResponseEntity<ApiResponse<Void>> reset(@Valid @RequestBody ResetPasswordRequest body,
                                                   HttpServletRequest request) {
        properties.requireEnabled();
        cookiePolicy.enforce(request);
        // Hash the exact case-sensitive token before using the identity-normalizing limiter.
        limiter.check("reset-password", request, hasher.hash(body.token()));
        passwords.resetPassword(body.token(), body.newPassword(), clientIpResolver.resolve(request));
        return loggedOut("Password reset. Sign in again.");
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Revoke all access and refresh sessions for the authenticated user")
    public ResponseEntity<ApiResponse<Void>> logoutAll(@AuthenticationPrincipal AuthenticatedUserPrincipal principal,
                                                      HttpServletRequest request) {
        properties.requireEnabled();
        cookiePolicy.enforce(request);
        limiter.check("logout-all", request, principal.getUserId().toString());
        passwords.logoutAll(principal, clientIpResolver.resolve(request));
        return loggedOut("Logged out of all sessions.");
    }

    private ResponseEntity<ApiResponse<Void>> loggedOut(String message) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", "").httpOnly(true)
                .secure(jwtConfig.isRefreshCookieSecure()).sameSite(jwtConfig.getRefreshCookieSameSite())
                .path("/api/v1/auth").maxAge(0).build();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .header("Set-Cookie", cookie.toString()).body(ApiResponse.ok(message, null));
    }
}
