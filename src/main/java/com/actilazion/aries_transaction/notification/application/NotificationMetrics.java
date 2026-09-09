package com.actilazion.aries_transaction.notification.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class NotificationMetrics {
    private final MeterRegistry registry;
    private final AtomicLong retained = new AtomicLong();

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("aries.notification.cleanup.retained", retained);
    }

    public void retainedBacklog(long count) {
        retained.set(count);
    }

    public void fanoutAccepted(int created) {
        registry.counter("aries.notification.fanout.accepted").increment();
        registry.counter("aries.notification.created").increment(created);
    }

    public void emailOutcome(String outcome) {
        Counter.builder("aries.notification.email.delivery")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void cleanup(int notifications, int attempts) {
        registry.counter("aries.notification.cleanup.deleted", "record", "notification")
                .increment(notifications);
        registry.counter("aries.notification.cleanup.deleted", "record", "email_attempt")
                .increment(attempts);
    }
}
