package com.actilazion.aries_transaction.identity.infrastructure;

import com.actilazion.aries_transaction.identity.application.PasswordResetCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "security.account-security", name = "cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class PasswordResetCleanupWorker {
    private final PasswordResetCleanupService service;

    @Scheduled(fixedDelayString = "${security.account-security.cleanup-interval-ms:3600000}")
    public void purgeExpired() {
        service.purgeExpired();
    }
}
