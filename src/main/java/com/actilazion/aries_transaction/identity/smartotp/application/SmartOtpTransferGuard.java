package com.actilazion.aries_transaction.identity.smartotp.application;

import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.domain.exception.UnauthorizedException;
import com.actilazion.aries_transaction.identity.smartotp.domain.*;
import com.actilazion.aries_transaction.identity.smartotp.infrastructure.SmartOtpRepository;
import com.actilazion.aries_transaction.identity.smartotp.infrastructure.SmartOtpRepository.Credential;
import com.actilazion.aries_transaction.transaction.domain.TransferPreview;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SmartOtpTransferGuard {
    private final SmartOtpProperties properties;
    private final SmartOtpChallengeService challenges;
    private final SmartOtpRepository repository;

    public Optional<Credential> lockCredential(User user) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal p
                && (!p.getUserId().equals(user.getId()) || p.getAuthVersion() != user.getAuthVersion()))
            throw new UnauthorizedException();
        return properties.getMode() == SmartOtpProperties.Mode.DISABLED ? Optional.empty() : repository.active(user.getId());
    }
    public boolean required(Account source, Account destination) {
        return challenges.enforced() && !source.getUser().getId().equals(destination.getUser().getId());
    }
    public String enrollmentState(UUID user) {
        if (properties.getMode() == SmartOtpProperties.Mode.DISABLED) return "UNAVAILABLE";
        // Caller uses the same user lock as lifecycle operations.
        return repository.active(user).isPresent() ? "ACTIVE" :
                repository.profile(user).everEnrolled() ? "RECOVERY_REQUIRED" : "NOT_ENROLLED";
    }
    public void authorize(User user, Optional<Credential> credential, TransferPreview preview, String key, UUID authorization) {
        if (!required(preview.getSourceAccount(), preview.getDestinationAccount()) && authorization == null) return;
        challenges.requireEnabled();
        var device = credential.orElseThrow(() -> SmartOtpException.conflict("SMART_OTP_ENROLLMENT_REQUIRED"));
        if (authorization == null) throw SmartOtpException.conflict("SMART_OTP_AUTHORIZATION_REQUIRED");
        var c = repository.challenge(authorization, user.getId()).orElseThrow(challenges::notFound);
        challenges.requireUsable(user, device, c, OtpPurpose.TRANSFER);
        if (!Objects.equals(c.previewId(), preview.getId()) || !Objects.equals(c.idempotencyKey(), key)
                || !Objects.equals(c.bindingHash(), challenges.binding(preview, key)))
            throw SmartOtpException.conflict("SMART_OTP_BINDING_CONFLICT");
        if (!"VERIFIED".equals(c.state())) throw SmartOtpException.conflict("SMART_OTP_AUTHORIZATION_REQUIRED");
        repository.state(authorization, "CONSUMED");
    }
    public void recheckDeadline(UUID user, UUID authorization) {
        if (authorization != null && !repository.challenge(authorization, user).orElseThrow(challenges::notFound)
                .expiresAt().isAfter(challenges.now())) throw SmartOtpException.conflict("SMART_OTP_EXPIRED");
    }
    public void legacy(Account from, Account to) {
        if (required(from, to)) throw SmartOtpException.conflict("SMART_OTP_AUTHORIZATION_REQUIRED");
    }
}
