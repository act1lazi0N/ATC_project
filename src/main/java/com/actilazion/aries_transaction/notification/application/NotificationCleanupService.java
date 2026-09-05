package com.actilazion.aries_transaction.notification.application;

import com.actilazion.aries_transaction.notification.infrastructure.EmailDeliveryAttemptRepository;
import com.actilazion.aries_transaction.notification.infrastructure.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class NotificationCleanupService {
    private final NotificationRepository notificationRepository;
    private final EmailDeliveryAttemptRepository attemptRepository;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;

    @Transactional
    public CleanupResult purgeExpired() {
        OffsetDateTime now = OffsetDateTime.now();
        int notifications = notificationRepository.deleteReadBefore(
                now.minusDays(properties.getRetentionDays()));
        int attempts = attemptRepository.deleteTerminalBefore(
                now.minusDays(properties.getDeliveryAttemptRetentionDays()));
        metrics.cleanup(notifications, attempts);
        return new CleanupResult(notifications, attempts);
    }

    public record CleanupResult(int notifications, int emailAttempts) {
    }
}
