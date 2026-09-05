package com.actilazion.aries_transaction.notification.infrastructure.email;

import com.actilazion.aries_transaction.notification.application.EmailDeliveryService;
import com.actilazion.aries_transaction.notification.application.EmailDeliveryWorkItem;
import com.actilazion.aries_transaction.notification.application.NotificationProperties;
import com.actilazion.aries_transaction.notification.application.NotificationMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.notification.email", name = "worker-enabled", havingValue = "true")
public class EmailDeliveryWorker {
    private final EmailDeliveryService service;
    private final EmailGateway gateway;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;

    @Scheduled(fixedDelayString = "${app.notification.email.poll-interval-ms:5000}")
    public void deliverPending() {
        for (EmailDeliveryWorkItem work : service.claim(properties.getEmail().getBatchSize())) {
            deliver(work);
        }
    }

    private void deliver(EmailDeliveryWorkItem work) {
        long started = System.nanoTime();
        try {
            gateway.send(work.message());
        } catch (EmailGatewayException ex) {
            service.markFailed(work, ex.isRetryable(), ex.getSafeCode(), elapsedMillis(started));
            metrics.emailOutcome(ex.isRetryable() ? "retryable_failure" : "terminal_failure");
            log.warn("[EMAIL] Delivery failed id={} attempt={} code={}",
                    work.deliveryId(), work.attemptNumber(), ex.getSafeCode());
            return;
        } catch (RuntimeException ex) {
            service.markFailed(work, true, "EMAIL_GATEWAY_FAILURE", elapsedMillis(started));
            metrics.emailOutcome("retryable_failure");
            log.warn("[EMAIL] Delivery failed id={} attempt={} code=EMAIL_GATEWAY_FAILURE",
                    work.deliveryId(), work.attemptNumber());
            return;
        }

        service.markDelivered(work, elapsedMillis(started));
        metrics.emailOutcome("delivered");
        log.info("[EMAIL] Delivery accepted id={} attempt={}", work.deliveryId(), work.attemptNumber());
    }

    private long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
