package com.actilazion.aries_transaction.identity.application;

import com.actilazion.aries_transaction.identity.infrastructure.PasswordResetChallengeRepository;
import com.actilazion.aries_transaction.notification.infrastructure.EmailDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class PasswordResetCleanupService {
    private final PasswordResetChallengeRepository challenges;
    private final EmailDeliveryRepository deliveries;
    private final AccountSecurityProperties properties;
    private final Clock clock;

    @Transactional
    public void purgeExpired() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        deliveries.cancelUnusablePasswordResets(now);
        OffsetDateTime cutoff = now.minusDays(properties.getRetentionDays());
        deliveries.deleteResolvedSecurityEmailsBefore(cutoff);
        challenges.deleteResolvedBefore(cutoff);
    }
}
