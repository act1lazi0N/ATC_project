package com.actilazion.aries_transaction.outbox;

import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.domain.OutboxEventStatus;
import com.actilazion.aries_transaction.outbox.infrastructure.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@Import(OutboxEventService.class)
class OutboxEventServiceIntegrationTest {
    @Autowired OutboxEventService outboxEventService;
    @Autowired OutboxEventRepository outboxEventRepository;

    @Test
    void claimPublishableEvents_marksPendingReadyFailedAndStaleProcessingAsProcessing() {
        OffsetDateTime now = OffsetDateTime.now();
        OutboxEvent pending = outboxEventRepository.save(event(OutboxEventStatus.PENDING, null));
        OutboxEvent readyFailed = outboxEventRepository.save(event(
                OutboxEventStatus.FAILED,
                now.minusSeconds(1)
        ));
        OutboxEvent unreadyFailed = outboxEventRepository.save(event(
                OutboxEventStatus.FAILED,
                now.plusMinutes(1)
        ));
        OutboxEvent staleProcessing = outboxEventRepository.save(event(
                OutboxEventStatus.PROCESSING,
                now.minusSeconds(1)
        ));
        OutboxEvent legacyStaleProcessing = outboxEventRepository.save(event(
                OutboxEventStatus.PROCESSING,
                null
        ));
        OutboxEvent activeProcessing = outboxEventRepository.save(event(
                OutboxEventStatus.PROCESSING,
                now.plusMinutes(1)
        ));
        outboxEventRepository.flush();

        var claimed = outboxEventService.claimPublishableEvents(25);

        assertThat(claimed)
                .extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(
                        pending.getId(),
                        readyFailed.getId(),
                        staleProcessing.getId(),
                        legacyStaleProcessing.getId()
                );
        assertThat(claimed)
                .allSatisfy(event -> {
                    assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PROCESSING);
                    assertThat(event.getNextAttemptAt()).isAfter(now);
                });
        assertThat(outboxEventRepository.findById(unreadyFailed.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.FAILED);
        assertThat(outboxEventRepository.findById(activeProcessing.getId()).orElseThrow().getNextAttemptAt())
                .isEqualTo(activeProcessing.getNextAttemptAt());
    }

    @Test
    void markFailed_recordsAttemptErrorAndBackoff() {
        OutboxEvent outboxEvent = outboxEventRepository.saveAndFlush(event(OutboxEventStatus.PROCESSING, null));

        outboxEventService.markFailed(outboxEvent.getId(), "remote 503");

        OutboxEvent failed = outboxEventRepository.findById(outboxEvent.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).isEqualTo("remote 503");
        assertThat(failed.getNextAttemptAt()).isAfter(OffsetDateTime.now());
    }

    @Test
    void markPublished_clearsProcessingLease() {
        OutboxEvent outboxEvent = outboxEventRepository.saveAndFlush(event(
                OutboxEventStatus.PROCESSING,
                OffsetDateTime.now().plusMinutes(5)
        ));

        outboxEventService.markPublished(outboxEvent.getId());

        OutboxEvent published = outboxEventRepository.findById(outboxEvent.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(published.getNextAttemptAt()).isNull();
    }

    private OutboxEvent event(OutboxEventStatus status, OffsetDateTime nextAttemptAt) {
        return OutboxEvent.builder()
                .aggregateType("Transaction")
                .aggregateId(UUID.randomUUID())
                .eventType("TransferCompleted")
                .payload(Map.of("transactionId", UUID.randomUUID().toString()))
                .status(status)
                .nextAttemptAt(nextAttemptAt)
                .build();
    }
}
