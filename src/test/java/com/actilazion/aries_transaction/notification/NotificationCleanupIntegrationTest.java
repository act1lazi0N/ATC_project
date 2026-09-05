package com.actilazion.aries_transaction.notification;

import com.actilazion.aries_transaction.notification.application.NotificationCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NotificationCleanupIntegrationTest {
    @Autowired NotificationCleanupService cleanupService;

    @Test
    void cleanupQueriesExecuteWhenNoRecordsAreExpired() {
        assertThat(cleanupService.purgeExpired())
                .isEqualTo(new NotificationCleanupService.CleanupResult(0, 0));
    }
}
