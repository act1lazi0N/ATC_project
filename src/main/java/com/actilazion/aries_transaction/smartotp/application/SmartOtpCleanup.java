package com.actilazion.aries_transaction.smartotp.application;

import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.smartotp.infrastructure.SmartOtpRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Component
@RequiredArgsConstructor
public class SmartOtpCleanup {
    private final SmartOtpProperties properties;
    private final SmartOtpRepository repository;
    private final SmartOtpChallengeService challenges;
    private final UserRepository users;
    private final PlatformTransactionManager transactions;

    @Scheduled(fixedDelayString="${app.smart-otp.cleanup-interval-ms:60000}")
    public void cleanup() {
        if (properties.getMode() == SmartOtpProperties.Mode.DISABLED) return;
        var now = challenges.now();
        for (var userId : repository.cleanupUsers(now)) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.findByIdWithLock(userId).orElseThrow();
                repository.cleanup(userId, now);
            });
        }
    }
}
