package com.actilazion.aries_transaction.outbox.infrastructure.worker;

import com.actilazion.aries_transaction.outbox.domain.OutboxEvent;
import com.actilazion.aries_transaction.outbox.application.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.outbox", name = "worker-enabled", havingValue = "true")
public class OutboxWorker {
    private final OutboxEventService outboxEventService;
    private final OutboxEventPublisher outboxEventPublisher;

    @Value("${app.outbox.batch-size:25}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventService.claimPublishableEvents(batchSize);
        if (events.isEmpty()) {
            return;
        }

        log.info("[OUTBOX] Publishing pending events count={}", events.size());
        for (OutboxEvent event : events) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            boolean accepted = outboxEventPublisher.publish(event);
            if (accepted) {
                outboxEventService.markPublished(event.getId(), event.getClaimToken());
                return;
            }

            log.info("[OUTBOX] Event remains pending id={} sink did not confirm durable acceptance", event.getId());
            outboxEventService.markFailed(
                    event.getId(),
                    event.getClaimToken(),
                    "Downstream sink did not confirm durable acceptance"
            );
        } catch (Exception ex) {
            log.error("[OUTBOX] Event delivery failed id={} error={}", event.getId(), ex.getMessage(), ex);
            outboxEventService.markFailed(event.getId(), event.getClaimToken(), ex.getMessage());
        }
    }
}
