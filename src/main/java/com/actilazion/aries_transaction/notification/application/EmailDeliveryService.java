package com.actilazion.aries_transaction.notification.application;

import com.actilazion.aries_transaction.audit.application.IdentityAuditService;
import com.actilazion.aries_transaction.audit.domain.IdentityAuditEventType;
import com.actilazion.aries_transaction.common.dto.PageResponse;
import com.actilazion.aries_transaction.common.exception.EmailDeliveryRedriveConflictException;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.EmailVerificationChallenge;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.notification.domain.EmailDelivery;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryAttempt;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryAttemptOutcome;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryPurpose;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryStatus;
import com.actilazion.aries_transaction.notification.domain.Notification;
import com.actilazion.aries_transaction.notification.domain.NotificationPreference;
import com.actilazion.aries_transaction.notification.dto.EmailDeliveryOperationsResponse;
import com.actilazion.aries_transaction.notification.infrastructure.EmailDeliveryAttemptRepository;
import com.actilazion.aries_transaction.notification.infrastructure.EmailDeliveryRepository;
import com.actilazion.aries_transaction.notification.infrastructure.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailDeliveryService {
    private static final List<EmailDeliveryStatus> PUBLISHABLE = List.of(
            EmailDeliveryStatus.PENDING, EmailDeliveryStatus.FAILED, EmailDeliveryStatus.PROCESSING);

    private final EmailDeliveryRepository deliveryRepository;
    private final EmailDeliveryAttemptRepository attemptRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final EmailTemplateRenderer renderer;
    private final NotificationProperties properties;
    private final IdentityAuditService identityAuditService;

    @Transactional
    public List<EmailDeliveryWorkItem> claim(int requestedLimit) {
        int limit = Math.clamp(requestedLimit, 1, 100);
        OffsetDateTime now = OffsetDateTime.now();
        List<EmailDelivery> deliveries = deliveryRepository.findPublishableWithLock(
                PUBLISHABLE, now, PageRequest.of(0, limit));
        List<EmailDeliveryWorkItem> work = new ArrayList<>();
        for (EmailDelivery delivery : deliveries) {
            String cancellation = cancellationReason(delivery, now);
            if (cancellation != null) {
                cancel(delivery, cancellation);
                continue;
            }
            delivery.setStatus(EmailDeliveryStatus.PROCESSING);
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            delivery.setCycleAttemptCount(delivery.getCycleAttemptCount() + 1);
            delivery.setClaimToken(UUID.randomUUID());
            delivery.setNextAttemptAt(now.plus(properties.getEmail().getProcessingLease()));
            delivery.setProviderMessageId(delivery.getId().toString());
            delivery.setLastErrorCode(null);
            work.add(new EmailDeliveryWorkItem(
                    delivery.getId(),
                    delivery.getClaimToken(),
                    delivery.getAttemptCount(),
                    renderer.render(delivery)
            ));
        }
        deliveryRepository.saveAllAndFlush(deliveries);
        return work;
    }

    @Transactional
    public void markDelivered(EmailDeliveryWorkItem work, long durationMs) {
        EmailDelivery delivery = claimed(work);
        attemptRepository.save(attempt(delivery, EmailDeliveryAttemptOutcome.DELIVERED,
                "SMTP_ACCEPTED", durationMs, null));
        delivery.setStatus(EmailDeliveryStatus.DELIVERED);
        delivery.setDeliveredAt(OffsetDateTime.now());
        delivery.setNextAttemptAt(null);
        delivery.setClaimToken(null);
        delivery.setLastErrorCode(null);
    }

    @Transactional
    public void markFailed(
            EmailDeliveryWorkItem work,
            boolean retryable,
            String safeCode,
            long durationMs
    ) {
        EmailDelivery delivery = claimed(work);
        boolean exhausted = delivery.getCycleAttemptCount() >= properties.getEmail().getMaxAttempts();
        EmailDeliveryAttemptOutcome outcome = retryable && !exhausted
                ? EmailDeliveryAttemptOutcome.RETRYABLE_FAILURE
                : EmailDeliveryAttemptOutcome.TERMINAL_FAILURE;
        attemptRepository.save(attempt(delivery, outcome, safeCode, durationMs, safeCode));
        delivery.setStatus(retryable && !exhausted
                ? EmailDeliveryStatus.FAILED
                : EmailDeliveryStatus.DEAD_LETTERED);
        delivery.setNextAttemptAt(retryable && !exhausted
                ? OffsetDateTime.now().plus(backoff(delivery.getCycleAttemptCount()))
                : null);
        delivery.setClaimToken(null);
        delivery.setLastErrorCode(truncate(safeCode, 100));
    }

    @Transactional(readOnly = true)
    public PageResponse<EmailDeliveryOperationsResponse> findByStatus(
            UUID actorId,
            EmailDeliveryStatus status,
            Pageable pageable
    ) {
        requireStaff(actorId);
        return PageResponse.from(deliveryRepository.findAllByStatus(status, pageable)
                .map(EmailDeliveryOperationsResponse::from));
    }

    @Transactional
    public EmailDelivery redrive(UUID actorId, UUID deliveryId, String ipAddress) {
        User actor = requireStaff(actorId);
        EmailDelivery delivery = deliveryRepository.findByIdForUpdate(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Email delivery", deliveryId));
        if (delivery.getStatus() != EmailDeliveryStatus.DEAD_LETTERED) {
            throw new EmailDeliveryRedriveConflictException();
        }
        delivery.setStatus(EmailDeliveryStatus.PENDING);
        delivery.setCycleAttemptCount(0);
        delivery.setRedriveCount(delivery.getRedriveCount() + 1);
        delivery.setNextAttemptAt(OffsetDateTime.now());
        delivery.setClaimToken(null);
        delivery.setLastErrorCode(null);
        identityAuditService.recordCustomerAdministration(
                IdentityAuditEventType.NOTIFICATION_EMAIL_REDRIVEN,
                recipientId(delivery),
                actor.getId(),
                ipAddress,
                Map.of("deliveryId", delivery.getId().toString())
        );
        return deliveryRepository.save(delivery);
    }

    private EmailDelivery claimed(EmailDeliveryWorkItem work) {
        EmailDelivery delivery = deliveryRepository.findByIdForUpdate(work.deliveryId())
                .orElseThrow(() -> new ResourceNotFoundException("Email delivery", work.deliveryId()));
        if (delivery.getStatus() != EmailDeliveryStatus.PROCESSING
                || !work.claimToken().equals(delivery.getClaimToken())) {
            throw new IllegalStateException("Email delivery claim is no longer current");
        }
        return delivery;
    }

    private String cancellationReason(EmailDelivery delivery, OffsetDateTime now) {
        if (delivery.getPurpose() == EmailDeliveryPurpose.EMAIL_VERIFICATION) {
            EmailVerificationChallenge challenge = delivery.getVerificationChallenge();
            if (!Boolean.TRUE.equals(challenge.getUser().getIsActive())) {
                return "USER_INACTIVE";
            }
            if (challenge.getUser().getEmailVerifiedAt() != null) {
                return "EMAIL_ALREADY_VERIFIED";
            }
            return challenge.isUsableAt(now) ? null : "VERIFICATION_CHALLENGE_EXPIRED";
        }
        Notification notification = delivery.getNotification();
        User recipient = notification.getRecipient();
        if (!Boolean.TRUE.equals(recipient.getIsActive())) {
            return "USER_INACTIVE";
        }
        if (recipient.getEmailVerifiedAt() == null) {
            return "EMAIL_NOT_VERIFIED";
        }
        NotificationPreference preference = preferenceRepository.findById(recipient.getId()).orElse(null);
        if (preference != null && !preference.emailEnabled(notification.getType().category())) {
            return "PREFERENCE_DISABLED";
        }
        return null;
    }

    private void cancel(EmailDelivery delivery, String reason) {
        delivery.setStatus(EmailDeliveryStatus.CANCELLED);
        delivery.setNextAttemptAt(null);
        delivery.setClaimToken(null);
        delivery.setLastErrorCode(reason);
    }

    private EmailDeliveryAttempt attempt(
            EmailDelivery delivery,
            EmailDeliveryAttemptOutcome outcome,
            String providerCode,
            long durationMs,
            String errorContext
    ) {
        return EmailDeliveryAttempt.builder()
                .delivery(delivery)
                .attemptNumber(delivery.getAttemptCount())
                .outcome(outcome)
                .providerCode(truncate(providerCode, 100))
                .durationMs(Math.max(0, durationMs))
                .errorContext(truncate(errorContext, 500))
                .build();
    }

    private Duration backoff(int cycleAttempt) {
        long seconds = Math.min(Duration.ofHours(6).toSeconds(), 60L << Math.min(cycleAttempt - 1, 8));
        long jitter = Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), Math.max(1, seconds / 5));
        return Duration.ofSeconds(seconds + jitter);
    }

    private User requireStaff(UUID actorId) {
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", actorId));
        if (actor.getRole() != Role.OPERATOR && actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Caller is not authorized for notification operations");
        }
        return actor;
    }

    private UUID recipientId(EmailDelivery delivery) {
        return delivery.getNotification() != null
                ? delivery.getNotification().getRecipient().getId()
                : delivery.getVerificationChallenge().getUser().getId();
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
