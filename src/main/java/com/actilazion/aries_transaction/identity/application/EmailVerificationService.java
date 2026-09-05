package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.audit.application.IdentityAuditService;
import com.actilazion.aries_transaction.audit.domain.IdentityAuditEventType;
import com.actilazion.aries_transaction.common.exception.InvalidEmailVerificationTokenException;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.EmailVerificationChallenge;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.dto.EmailVerificationStatusResponse;
import com.actilazion.aries_transaction.identity.infrastructure.EmailVerificationChallengeRepository;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.notification.application.NotificationProperties;
import com.actilazion.aries_transaction.notification.domain.EmailDelivery;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryPurpose;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryStatus;
import com.actilazion.aries_transaction.notification.infrastructure.EmailDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {
    private final UserRepository userRepository;
    private final EmailVerificationChallengeRepository challengeRepository;
    private final EmailDeliveryRepository emailDeliveryRepository;
    private final EmailVerificationTokenService tokenService;
    private final NotificationProperties properties;
    private final IdentityAuditService identityAuditService;

    @Transactional
    public EmailVerificationStatusResponse request(UUID userId, String ipAddress) {
        User user = userRepository.findByIdWithLock(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getEmailVerifiedAt() != null) {
            return new EmailVerificationStatusResponse(true);
        }
        tokenService.requireConfigured();

        OffsetDateTime now = OffsetDateTime.now();
        challengeRepository.invalidateActiveByUserId(userId, now);
        EmailVerificationChallenge challenge = challengeRepository.saveAndFlush(
                EmailVerificationChallenge.builder()
                        .user(user)
                        .expiresAt(now.plus(properties.getEmail().getVerificationTtl()))
                        .build());
        emailDeliveryRepository.save(EmailDelivery.builder()
                .id(UUID.randomUUID())
                .purpose(EmailDeliveryPurpose.EMAIL_VERIFICATION)
                .verificationChallenge(challenge)
                .status(EmailDeliveryStatus.PENDING)
                .build());
        identityAuditService.record(
                IdentityAuditEventType.EMAIL_VERIFICATION_REQUESTED,
                userId,
                user.getEmail(),
                ipAddress,
                Map.of("challengeId", challenge.getId().toString())
        );
        return new EmailVerificationStatusResponse(false);
    }

    @Transactional
    public EmailVerificationStatusResponse confirm(String token, String ipAddress) {
        UUID challengeId;
        try {
            challengeId = tokenService.challengeId(token);
        } catch (IllegalArgumentException ex) {
            throw new InvalidEmailVerificationTokenException();
        }
        EmailVerificationChallenge challenge = challengeRepository.findByIdWithUserForUpdate(challengeId)
                .orElseThrow(InvalidEmailVerificationTokenException::new);
        OffsetDateTime now = OffsetDateTime.now();
        if (!challenge.isUsableAt(now) || !tokenService.matches(token, challenge)) {
            throw new InvalidEmailVerificationTokenException();
        }

        User user = challenge.getUser();
        challenge.setConsumedAt(now);
        user.setEmailVerifiedAt(now);
        challengeRepository.invalidateActiveByUserId(user.getId(), now);
        userRepository.save(user);
        identityAuditService.record(
                IdentityAuditEventType.EMAIL_VERIFICATION_COMPLETED,
                user.getId(),
                user.getEmail(),
                ipAddress,
                Map.of("challengeId", challenge.getId().toString())
        );
        return new EmailVerificationStatusResponse(true);
    }
}
