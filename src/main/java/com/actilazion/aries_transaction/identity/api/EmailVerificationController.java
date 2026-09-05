package com.actilazion.aries_transaction.identity.api;

import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.config.AuthRateLimiter;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.identity.application.EmailVerificationService;
import com.actilazion.aries_transaction.identity.dto.ConfirmEmailVerificationRequest;
import com.actilazion.aries_transaction.identity.dto.EmailVerificationStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/email-verification")
@RequiredArgsConstructor
public class EmailVerificationController {
    private static final String NO_STORE = "no-store";
    private final EmailVerificationService service;
    private final AuthRateLimiter rateLimiter;

    @PostMapping("/request")
    public ResponseEntity<ApiResponse<EmailVerificationStatusResponse>> request(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            HttpServletRequest request
    ) {
        rateLimiter.check("email-verification-request", request, principal.getEmail());
        return ResponseEntity.accepted().header("Cache-Control", NO_STORE).body(ApiResponse.ok(
                "Email verification request accepted",
                service.request(principal.getUserId(), request.getRemoteAddr())
        ));
    }

    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<EmailVerificationStatusResponse>> confirm(
            @Valid @RequestBody ConfirmEmailVerificationRequest body,
            HttpServletRequest request
    ) {
        rateLimiter.check("email-verification-confirm", request, null);
        return ResponseEntity.ok().header("Cache-Control", NO_STORE).body(ApiResponse.ok(
                "Email verified",
                service.confirm(body.token(), request.getRemoteAddr())
        ));
    }
}
