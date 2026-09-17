package com.actilazion.aries_transaction.smartotp.dto;

import com.actilazion.aries_transaction.smartotp.domain.OtpPurpose;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class SmartOtpDtos {
    private SmartOtpDtos() {}
    public record Status(String mode, String enrollmentState, UUID deviceId) {}
    public record ChallengeView(UUID id, UUID deviceId, OtpPurpose purpose, String suite,
                                String payloadBase64, String state, OffsetDateTime expiresAt) {}
    public record Enrollment(UUID id, String secretBase64, ChallengeView challenge) {
        @Override public String toString() { return "Enrollment[redacted]"; }
    }
    public record RecoveryCodes(List<String> recoveryCodes) {
        @Override public String toString() { return "RecoveryCodes[redacted]"; }
    }
    public record RecoveryGrant(String enrollmentGrant, OffsetDateTime expiresAt) {
        @Override public String toString() { return "RecoveryGrant[redacted]"; }
    }
    public record EnrollRequest(@NotBlank @Size(max=72) String currentPassword, UUID authorizationId,
                                @Size(max=8) String otp, @Size(max=128) String enrollmentGrant) {
        @Override public String toString() { return "EnrollRequest[redacted]"; }
    }
    public record Proof(@NotNull UUID authorizationId, @NotBlank @Pattern(regexp="[0-9]{8}") String otp) {
        @Override public String toString() { return "Proof[redacted]"; }
    }
    public record ManagementRequest(@NotBlank @Size(max=72) String currentPassword,
                                     @NotNull UUID authorizationId, @NotBlank @Pattern(regexp="[0-9]{8}") String otp) {
        @Override public String toString() { return "ManagementRequest[redacted]"; }
    }
    public record RecoverRequest(@NotBlank @Size(max=72) String currentPassword,
                                 @NotBlank @Size(max=128) String recoveryCode) {
        @Override public String toString() { return "RecoverRequest[redacted]"; }
    }
    public record ManagementChallengeRequest(@NotNull OtpPurpose purpose) {}
    public record AuthorizationRequest(@NotNull UUID previewId, @NotBlank @Size(min=16,max=64) String idempotencyKey) {}
    public record VerifyRequest(@NotBlank @Pattern(regexp="[0-9]{8}") String otp) {
        @Override public String toString() { return "VerifyRequest[redacted]"; }
    }
}
