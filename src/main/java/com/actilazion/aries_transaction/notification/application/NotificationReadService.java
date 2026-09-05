package com.actilazion.aries_transaction.notification.application;

import com.actilazion.aries_transaction.common.dto.PageResponse;
import com.actilazion.aries_transaction.common.exception.NotificationPreferenceVersionConflictException;
import com.actilazion.aries_transaction.common.exception.ResourceNotFoundException;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryPurpose;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryStatus;
import com.actilazion.aries_transaction.notification.domain.Notification;
import com.actilazion.aries_transaction.notification.domain.NotificationPreference;
import com.actilazion.aries_transaction.notification.domain.NotificationReadStatus;
import com.actilazion.aries_transaction.notification.dto.MarkAllReadResponse;
import com.actilazion.aries_transaction.notification.dto.NotificationPreferenceResponse;
import com.actilazion.aries_transaction.notification.dto.NotificationResponse;
import com.actilazion.aries_transaction.notification.dto.UnreadCountResponse;
import com.actilazion.aries_transaction.notification.dto.UpdateNotificationPreferenceRequest;
import com.actilazion.aries_transaction.notification.infrastructure.EmailDeliveryRepository;
import com.actilazion.aries_transaction.notification.infrastructure.NotificationPreferenceRepository;
import com.actilazion.aries_transaction.notification.infrastructure.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationReadService {
    private static final List<EmailDeliveryStatus> CANCELLABLE = List.of(
            EmailDeliveryStatus.PENDING, EmailDeliveryStatus.FAILED);

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final EmailDeliveryRepository emailDeliveryRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> find(UUID recipientId, NotificationReadStatus status, Pageable pageable) {
        var page = switch (status) {
            case ALL -> notificationRepository.findAllByRecipient_Id(recipientId, pageable);
            case UNREAD -> notificationRepository.findAllByRecipient_IdAndReadAtIsNull(recipientId, pageable);
            case READ -> notificationRepository.findAllByRecipient_IdAndReadAtIsNotNull(recipientId, pageable);
        };
        return PageResponse.from(page.map(NotificationResponse::from));
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(UUID recipientId) {
        return new UnreadCountResponse(notificationRepository.countByRecipient_IdAndReadAtIsNull(recipientId));
    }

    @Transactional
    public NotificationResponse markRead(UUID recipientId, UUID notificationId) {
        Notification notification = notificationRepository.findOwnedByIdForUpdate(notificationId, recipientId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", notificationId));
        if (notification.getReadAt() == null) {
            notification.setReadAt(OffsetDateTime.now());
        }
        return NotificationResponse.from(notificationRepository.save(notification));
    }

    @Transactional
    public MarkAllReadResponse markAllRead(UUID recipientId) {
        OffsetDateTime now = OffsetDateTime.now();
        int updated = notificationRepository.markAllRead(recipientId, now, now);
        return new MarkAllReadResponse(updated, now);
    }

    @Transactional(readOnly = true)
    public NotificationPreferenceResponse preferences(UUID userId) {
        User user = requireUser(userId);
        return preferenceRepository.findById(userId)
                .map(preference -> response(preference, user))
                .orElse(new NotificationPreferenceResponse(true, true, user.getEmailVerifiedAt() != null, 0));
    }

    @Transactional
    public NotificationPreferenceResponse updatePreferences(
            UUID userId,
            UpdateNotificationPreferenceRequest request
    ) {
        User user = userRepository.findByIdWithLock(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        NotificationPreference preference = preferenceRepository.findByUserIdForUpdate(userId).orElse(null);
        if (preference == null) {
            if (request.expectedVersion() != 0) {
                throw new NotificationPreferenceVersionConflictException();
            }
            preference = NotificationPreference.builder().user(user).build();
        } else if (preference.getVersion() != request.expectedVersion()) {
            throw new NotificationPreferenceVersionConflictException();
        }

        preference.setTransactionEmailEnabled(request.transactionEmailEnabled());
        preference.setWebhookAlertEmailEnabled(request.webhookAlertEmailEnabled());
        NotificationPreference saved = preferenceRepository.saveAndFlush(preference);
        if (!saved.isTransactionEmailEnabled()) {
            emailDeliveryRepository.cancelPendingForRecipient(
                    userId, EmailDeliveryPurpose.TRANSACTION_NOTIFICATION, CANCELLABLE);
        }
        if (!saved.isWebhookAlertEmailEnabled()) {
            emailDeliveryRepository.cancelPendingForRecipient(
                    userId, EmailDeliveryPurpose.WEBHOOK_ALERT, CANCELLABLE);
        }
        return response(saved, user);
    }

    private NotificationPreferenceResponse response(NotificationPreference preference, User user) {
        return new NotificationPreferenceResponse(
                preference.isTransactionEmailEnabled(),
                preference.isWebhookAlertEmailEnabled(),
                user.getEmailVerifiedAt() != null,
                preference.getVersion()
        );
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}
