package com.actilazion.aries_transaction.notification.infrastructure;

import com.actilazion.aries_transaction.notification.application.NotificationCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.notification",
        name = "cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class NotificationCleanupWorker {
    private final NotificationCleanupService cleanupService;

    @Scheduled(fixedDelayString = "${app.notification.cleanup-interval-ms:3600000}")
    public void purgeExpired() {
        var result = cleanupService.purgeExpired();
        if (result.notifications() > 0 || result.emailAttempts() > 0) {
            log.info("[NOTIFICATION] Retention cleanup notifications={} emailAttempts={}",
                    result.notifications(), result.emailAttempts());
        }
    }
}
