package com.actilazion.aries_transaction.notification;

import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.identity.infrastructure.UserRepository;
import com.actilazion.aries_transaction.notification.application.*;
import com.actilazion.aries_transaction.notification.domain.*;
import com.actilazion.aries_transaction.notification.infrastructure.*;
import com.actilazion.aries_transaction.outbox.domain.*;
import com.actilazion.aries_transaction.outbox.infrastructure.OutboxEventRepository;
import com.actilazion.aries_transaction.support.PostgresIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class EmailDeliveryPostgresIntegrationTest extends PostgresIntegrationTestSupport {
    @Autowired EmailDeliveryService service;
    @Autowired EmailDeliveryRepository deliveries;
    @Autowired EmailDeliveryAttemptRepository attempts;
    @Autowired NotificationRepository notifications;
    @Autowired UserRepository users;
    @Autowired OutboxEventRepository events;
    @Autowired NotificationCleanupService cleanup;
    @Autowired NotificationProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry metrics;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean EmailTemplateRenderer renderer;

    @BeforeEach
    void clearNotifications() {
        jdbc.update("DELETE FROM notifications");
    }

    @Test
    void competingWorkersCannotOwnTheSameCurrentClaim() throws Exception {
        EmailDelivery delivery = delivery(EmailDeliveryStatus.PENDING);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return service.claim(delivery.getId()); });
            var second = executor.submit(() -> { start.await(); return service.claim(delivery.getId()); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .filteredOn(Optional::isPresent).hasSize(1);
        }
        assertThat(deliveries.findById(delivery.getId()).orElseThrow().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void candidateSelectionDoesNotPreassignLeasesAndClaimRechecksAfterWaitingForLock() throws Exception {
        EmailDelivery first = delivery(EmailDeliveryStatus.PENDING);
        EmailDelivery second = delivery(EmailDeliveryStatus.PENDING);
        assertThat(service.findDueIds(25)).contains(first.getId(), second.getId());
        service.claim(first.getId()).orElseThrow();
        assertThat(deliveries.findById(second.getId()).orElseThrow().getNextAttemptAt()).isNull();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                var row = deliveries.findByIdForUpdate(second.getId()).orElseThrow();
                row.setStatus(EmailDeliveryStatus.PROCESSING);
                row.setClaimToken(UUID.randomUUID());
                row.setNextAttemptAt(OffsetDateTime.now().plusMinutes(5));
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var claimant = executor.submit(() -> service.claim(second.getId()));
            try {
                assertThatThrownBy(() -> claimant.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            assertThat(claimant.get(10, TimeUnit.SECONDS)).isEmpty();
        }
    }

    @Test
    void expiredClaimsAreFencedAndExhaustedUnknownOutcomesAreDeadLettered() {
        EmailDelivery delivery = delivery(EmailDeliveryStatus.PENDING);
        EmailDeliveryWorkItem original = service.claim(delivery.getId()).orElseThrow();
        expire(delivery.getId());
        EmailDeliveryWorkItem replacement = service.claim(delivery.getId()).orElseThrow();
        assertThat(replacement.claimToken()).isNotEqualTo(original.claimToken());
        assertThatThrownBy(() -> service.markDelivered(original, 1)).hasMessageContaining("no longer current");
        assertThatThrownBy(() -> service.markFailed(original, true, "SMTP_TEMPORARY_FAILURE", 1))
                .hasMessageContaining("no longer current");
        assertThat(attempts.findAllByDelivery_IdOrderByAttemptNumber(delivery.getId()))
                .singleElement().extracting(EmailDeliveryAttempt::getProviderCode).isEqualTo("DELIVERY_OUTCOME_UNKNOWN");
        jdbc.update("UPDATE email_deliveries SET cycle_attempt_count = ?, next_attempt_at = ? WHERE id = ?",
                properties.getEmail().getMaxAttempts(), OffsetDateTime.now().minusMinutes(1), delivery.getId());
        assertThat(service.claim(delivery.getId())).isEmpty();
        var exhausted = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(EmailDeliveryStatus.DEAD_LETTERED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(2);
        assertThat(exhausted.getLastErrorCode()).isEqualTo("DELIVERY_OUTCOME_UNKNOWN");
        assertThat(exhausted.getClaimToken()).isNull();
        assertThat(exhausted.getDeliveredAt()).isNull();
        assertThat(service.claim(delivery.getId())).isEmpty();
        assertThat(attempts.findAllByDelivery_IdOrderByAttemptNumber(delivery.getId())).hasSize(2);
    }

    @Test
    void ordinaryFailureExhaustionAndSuccessfulAcceptancePersistTruthfulResults() {
        EmailDelivery failed = delivery(EmailDeliveryStatus.PENDING);
        jdbc.update("UPDATE email_deliveries SET cycle_attempt_count = ? WHERE id = ?",
                properties.getEmail().getMaxAttempts() - 1, failed.getId());
        var work = service.claim(failed.getId()).orElseThrow();
        service.markFailed(work, true, "SMTP_TEMPORARY_FAILURE", 10);
        assertThat(deliveries.findById(failed.getId()).orElseThrow().getStatus()).isEqualTo(EmailDeliveryStatus.DEAD_LETTERED);
        EmailDelivery accepted = delivery(EmailDeliveryStatus.PENDING);
        service.markDelivered(service.claim(accepted.getId()).orElseThrow(), 10);
        assertThat(deliveries.findById(accepted.getId()).orElseThrow().getStatus()).isEqualTo(EmailDeliveryStatus.DELIVERED);
    }

    @Test
    void rolledBackAcceptanceRemainsProcessingUntilLeaseRecovery() {
        var delivery = delivery(EmailDeliveryStatus.PENDING);
        var work = service.claim(delivery.getId()).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            service.markDelivered(work, 10);
            deliveries.flush();
            tx.setRollbackOnly();
        });
        var uncertain = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(uncertain.getStatus()).isEqualTo(EmailDeliveryStatus.PROCESSING);
        assertThat(uncertain.getDeliveredAt()).isNull();
        assertThat(attempts.findAllByDelivery_IdOrderByAttemptNumber(delivery.getId())).isEmpty();
        assertThat(service.claim(delivery.getId())).isEmpty();
        expire(delivery.getId());
        assertThat(service.claim(delivery.getId())).isPresent();
        assertThat(attempts.findAllByDelivery_IdOrderByAttemptNumber(delivery.getId()))
                .singleElement().extracting(EmailDeliveryAttempt::getProviderCode).isEqualTo("DELIVERY_OUTCOME_UNKNOWN");
    }

    @Test
    void renderingFailureDoesNotRollbackOrBlockUnrelatedMessages() {
        EmailDelivery poison = delivery(EmailDeliveryStatus.PENDING);
        EmailDelivery healthy = delivery(EmailDeliveryStatus.PENDING);
        doThrow(new IllegalArgumentException("private message content"))
                .when(renderer).render(argThat(row -> row.getId().equals(poison.getId())));
        assertThat(service.claim(poison.getId())).isEmpty();
        var failed = deliveries.findById(poison.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(EmailDeliveryStatus.DEAD_LETTERED);
        assertThat(failed.getLastErrorCode()).isEqualTo("EMAIL_RENDERING_FAILURE");
        assertThat(service.claim(healthy.getId())).isPresent();
    }

    @Test
    void cancellationEligibilityIsRecheckedAtClaimTime() {
        EmailDelivery delivery = delivery(EmailDeliveryStatus.PENDING);
        jdbc.update("UPDATE users SET is_active = false WHERE id = (SELECT recipient_user_id FROM notifications WHERE id = ?)",
                delivery.getNotification().getId());
        assertThat(service.claim(delivery.getId())).isEmpty();
        assertThat(deliveries.findById(delivery.getId()).orElseThrow().getStatus()).isEqualTo(EmailDeliveryStatus.CANCELLED);
    }

    @Test
    void retentionPreservesEveryUnresolvedStateAndItsAttemptHistory() {
        Map<EmailDeliveryStatus, EmailDelivery> fixture = new EnumMap<>(EmailDeliveryStatus.class);
        for (EmailDeliveryStatus status : EmailDeliveryStatus.values()) {
            var delivery = delivery(status);
            fixture.put(status, delivery);
            attempts.saveAndFlush(EmailDeliveryAttempt.builder().delivery(delivery).attemptNumber(1)
                    .outcome(EmailDeliveryAttemptOutcome.RETRYABLE_FAILURE).durationMs(0).build());
        }
        jdbc.update("UPDATE email_delivery_attempts SET created_at = ?", OffsetDateTime.now().minusDays(365));
        Notification withoutEmail = notification(publishedEvent());
        Notification unread = notification(publishedEvent());
        unread.setReadAt(null);
        notifications.saveAndFlush(unread);
        assertThat(cleanup.purgeExpired().notifications()).isEqualTo(3);
        for (var entry : fixture.entrySet()) {
            boolean retain = entry.getKey() != EmailDeliveryStatus.DELIVERED && entry.getKey() != EmailDeliveryStatus.CANCELLED;
            assertThat(notifications.existsById(entry.getValue().getNotification().getId())).isEqualTo(retain);
            assertThat(deliveries.existsById(entry.getValue().getId())).isEqualTo(retain);
            assertThat(attempts.findAllByDelivery_IdOrderByAttemptNumber(entry.getValue().getId())).hasSize(retain ? 1 : 0);
        }
        assertThat(notifications.existsById(withoutEmail.getId())).isFalse();
        assertThat(notifications.existsById(unread.getId())).isTrue();
        assertThat(metrics.get("aries.notification.cleanup.retained").gauge().value()).isEqualTo(4);
    }

    @Test
    void retentionKeepsDeduplicationEvidenceUntilOutboxPublication() {
        OutboxEvent event = publishedEvent();
        event.setStatus(OutboxEventStatus.PROCESSING);
        events.saveAndFlush(event);
        Notification notification = notification(event);
        cleanup.purgeExpired();
        assertThat(notifications.findRecipientIdsByOutboxEventId(event.getId())).contains(notification.getRecipient().getId());
        event.setStatus(OutboxEventStatus.PUBLISHED);
        events.saveAndFlush(event);
        cleanup.purgeExpired();
        assertThat(notifications.existsById(notification.getId())).isFalse();
    }

    @Test
    void cleanupRacingWithClaimAndStaffRedrivePreservesRecoverableWork() throws Exception {
        EmailDelivery pending = delivery(EmailDeliveryStatus.PENDING);
        EmailDelivery dead = delivery(EmailDeliveryStatus.DEAD_LETTERED);
        User staff = users.saveAndFlush(User.builder().fullName("Operator").email(UUID.randomUUID() + "@test.local")
                .passwordHash("hashed").role(Role.OPERATOR).isActive(true).build());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var purge = executor.submit(() -> { start.await(); return cleanup.purgeExpired(); });
            var claim = executor.submit(() -> { start.await(); return service.claim(pending.getId()); });
            var redrive = executor.submit(() -> { start.await(); return service.redrive(staff.getId(), dead.getId(), null); });
            start.countDown();
            purge.get(15, TimeUnit.SECONDS);
            assertThat(claim.get(15, TimeUnit.SECONDS)).isPresent();
            assertThat(redrive.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(EmailDeliveryStatus.PENDING);
        }
        assertThat(deliveries.existsById(pending.getId())).isTrue();
        assertThat(deliveries.existsById(dead.getId())).isTrue();
        assertThat(service.claim(dead.getId())).isPresent();
    }

    private void expire(UUID id) {
        jdbc.update("UPDATE email_deliveries SET next_attempt_at = ? WHERE id = ?", OffsetDateTime.now().minusMinutes(1), id);
    }

    private EmailDelivery delivery(EmailDeliveryStatus status) {
        return deliveries.saveAndFlush(EmailDelivery.builder().id(UUID.randomUUID())
                .purpose(EmailDeliveryPurpose.TRANSACTION_NOTIFICATION).notification(notification(publishedEvent()))
                .status(status).build());
    }

    private OutboxEvent publishedEvent() {
        return events.saveAndFlush(OutboxEvent.builder().aggregateType("TRANSACTION").aggregateId(UUID.randomUUID())
                .eventType("TransferCompleted").status(OutboxEventStatus.PUBLISHED).payload(Map.of()).build());
    }

    private Notification notification(OutboxEvent event) {
        User user = users.saveAndFlush(User.builder().fullName("Notification test").email(UUID.randomUUID() + "@test.local")
                .passwordHash("hashed").role(Role.USER).isActive(true).emailVerifiedAt(OffsetDateTime.now()).build());
        return notifications.saveAndFlush(Notification.builder().id(UUID.randomUUID()).recipient(user)
                .sourceKind(NotificationSourceKind.OUTBOX_EVENT).sourceId(event.getId()).sourceVersion(1)
                .type(NotificationType.TRANSFER_COMPLETED).title("Transfer completed").message("Transfer completed")
                .payload(Map.of()).occurredAt(OffsetDateTime.now().minusDays(100))
                .readAt(OffsetDateTime.now().minusDays(100)).build());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
