package com.actilazion.aries_transaction.notification.application;

import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.notification.domain.EmailDelivery;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryPurpose;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryStatus;
import com.actilazion.aries_transaction.notification.domain.Notification;
import com.actilazion.aries_transaction.notification.domain.NotificationCategory;
import com.actilazion.aries_transaction.notification.domain.NotificationPreference;
import com.actilazion.aries_transaction.notification.domain.NotificationSourceKind;
import com.actilazion.aries_transaction.notification.domain.NotificationType;
import com.actilazion.aries_transaction.notification.infrastructure.EmailDeliveryRepository;
import com.actilazion.aries_transaction.notification.infrastructure.NotificationPreferenceRepository;
import com.actilazion.aries_transaction.notification.infrastructure.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class NotificationCreator {
    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final EmailDeliveryRepository emailDeliveryRepository;

    public boolean create(
            User recipient,
            NotificationSourceKind sourceKind,
            UUID sourceId,
            long sourceVersion,
            NotificationType type,
            String title,
            String message,
            Map<String, Object> payload,
            OffsetDateTime occurredAt
    ) {
        if (notificationRepository.existsBySourceKindAndSourceIdAndSourceVersionAndRecipient_IdAndType(
                sourceKind, sourceId, sourceVersion, recipient.getId(), type)) {
            return false;
        }

        Notification notification = notificationRepository.save(Notification.builder()
                .id(UUID.randomUUID())
                .recipient(recipient)
                .sourceKind(sourceKind)
                .sourceId(sourceId)
                .sourceVersion(sourceVersion)
                .type(type)
                .title(title)
                .message(message)
                .payload(Collections.unmodifiableMap(new LinkedHashMap<>(payload)))
                .occurredAt(occurredAt)
                .build());

        if (shouldQueueEmail(recipient, type.category())) {
            emailDeliveryRepository.save(EmailDelivery.builder()
                    .id(UUID.randomUUID())
                    .purpose(type.category() == NotificationCategory.TRANSACTION
                            ? EmailDeliveryPurpose.TRANSACTION_NOTIFICATION
                            : EmailDeliveryPurpose.WEBHOOK_ALERT)
                    .notification(notification)
                    .status(EmailDeliveryStatus.PENDING)
                    .build());
        }
        return true;
    }

    private boolean shouldQueueEmail(User recipient, NotificationCategory category) {
        if (!Boolean.TRUE.equals(recipient.getIsActive()) || recipient.getEmailVerifiedAt() == null) {
            return false;
        }
        return preferenceRepository.findById(recipient.getId())
                .map(preference -> preference.emailEnabled(category))
                .orElse(true);
    }
}
