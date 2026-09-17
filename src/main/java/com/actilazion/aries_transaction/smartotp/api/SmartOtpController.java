package com.actilazion.aries_transaction.smartotp.api;

import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.smartotp.application.*;
import com.actilazion.aries_transaction.smartotp.domain.OtpPurpose;
import com.actilazion.aries_transaction.smartotp.dto.SmartOtpDtos.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name="Smart OTP", description="Device enrollment, recovery and transaction-bound OCRA proofs")
@SecurityRequirement(name="bearerAuth")
public class SmartOtpController {
    private final SmartOtpLifecycleService lifecycle;
    private final SmartOtpChallengeService challenges;
    private final SmartOtpVerificationService verification;
    private final SmartOtpRequestProtection protection;

    @GetMapping("/auth/smart-otp/status")
    public ResponseEntity<ApiResponse<Status>> status(@AuthenticationPrincipal AuthenticatedUserPrincipal p, HttpServletRequest http) {
        protection.check(http, p.getUserId()); return ok(lifecycle.status(p));
    }
    @PostMapping("/auth/smart-otp/enrollments")
    public ResponseEntity<ApiResponse<Enrollment>> enroll(@AuthenticationPrincipal AuthenticatedUserPrincipal p,
            @Valid @RequestBody EnrollRequest request, HttpServletRequest http) {
        protection.check(http, p.getUserId());
        if (request.authorizationId() != null)
            verification.verify(p, request.authorizationId(), OtpPurpose.REPLACE_DEVICE, request.otp()).requireSuccess();
        return ok(lifecycle.enroll(p, request.currentPassword(), request.authorizationId(), request.enrollmentGrant()));
    }
    @PostMapping("/auth/smart-otp/enrollments/{id}/confirm")
    public ResponseEntity<ApiResponse<RecoveryCodes>> confirm(@AuthenticationPrincipal AuthenticatedUserPrincipal p,
            @PathVariable UUID id, @Valid @RequestBody Proof proof, HttpServletRequest http) {
        protection.check(http, p.getUserId());
        verification.verify(p, proof.authorizationId(), OtpPurpose.ENROLLMENT, proof.otp()).requireSuccess();
        return ok(lifecycle.confirm(p, id, proof.authorizationId()));
    }
    @PostMapping("/auth/smart-otp/management-challenges")
    public ResponseEntity<ApiResponse<ChallengeView>> management(@AuthenticationPrincipal AuthenticatedUserPrincipal p,
            @Valid @RequestBody ManagementChallengeRequest request, HttpServletRequest http) {
        protection.check(http, p.getUserId()); return ok(challenges.management(p, request.purpose()));
    }
    @PostMapping("/auth/smart-otp/devices/{id}/revoke")
    public ResponseEntity<ApiResponse<Void>> revoke(@AuthenticationPrincipal AuthenticatedUserPrincipal p,
            @PathVariable UUID id, @Valid @RequestBody ManagementRequest request, HttpServletRequest http) {
        protection.check(http, p.getUserId());
        verification.verify(p, request.authorizationId(), OtpPurpose.REVOKE_DEVICE, request.otp()).requireSuccess();
        lifecycle.revoke(p, id, request.currentPassword(), request.authorizationId()); return ok(null);
    }
    @PostMapping("/auth/smart-otp/recovery-codes/regenerate")
    public ResponseEntity<ApiResponse<RecoveryCodes>> regenerate(@AuthenticationPrincipal AuthenticatedUserPrincipal p,
            @Valid @RequestBody ManagementRequest request, HttpServletRequest http) {
        protection.check(http, p.getUserId());
        verification.verify(p, request.authorizationId(), OtpPurpose.REGENERATE_RECOVERY_CODES, request.otp()).requireSuccess();
        return ok(lifecycle.regenerate(p, request.currentPassword(), request.authorizationId()));
    }
    @PostMapping("/auth/smart-otp/recover")
    public ResponseEntity<ApiResponse<RecoveryGrant>> recover(@AuthenticationPrincipal AuthenticatedUserPrincipal p,
            @Valid @RequestBody RecoverRequest request, HttpServletRequest http) {
        protection.check(http, p.getUserId()); return ok(lifecycle.recover(p, request.currentPassword(), request.recoveryCode()));
    }
    @PostMapping("/transfers/authorizations")
    public ResponseEntity<ApiResponse<ChallengeView>> authorize(@AuthenticationPrincipal AuthenticatedUserPrincipal p,
            @Valid @RequestBody AuthorizationRequest request, HttpServletRequest http) {
        protection.check(http, p.getUserId()); return ok(challenges.authorize(p, request.previewId(), request.idempotencyKey()));
    }
    @GetMapping("/transfers/authorizations/{id}")
    public ResponseEntity<ApiResponse<ChallengeView>> read(@AuthenticationPrincipal AuthenticatedUserPrincipal p,
            @PathVariable UUID id, HttpServletRequest http) {
        protection.check(http, p.getUserId()); return ok(challenges.read(p, id));
    }
    @PostMapping("/transfers/authorizations/{id}/verify")
    public ResponseEntity<ApiResponse<ChallengeView>> verify(@AuthenticationPrincipal AuthenticatedUserPrincipal p,
            @PathVariable UUID id, @Valid @RequestBody VerifyRequest request, HttpServletRequest http) {
        protection.check(http, p.getUserId());
        verification.verify(p, id, OtpPurpose.TRANSFER, request.otp()).requireSuccess();
        return ok(challenges.read(p, id));
    }
    private <T> ResponseEntity<ApiResponse<T>> ok(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.ok("Smart OTP request completed", body));
    }
}
