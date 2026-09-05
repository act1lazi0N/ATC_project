package com.actilazion.aries_transaction.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "email_delivery_attempts", uniqueConstraints = @UniqueConstraint(
        name = "uk_email_delivery_attempts_number", columnNames = {"delivery_id", "attempt_number"}
))
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailDeliveryAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private EmailDelivery delivery;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EmailDeliveryAttemptOutcome outcome;

    @Column(name = "provider_code", length = 100)
    private String providerCode;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "error_context", length = 500)
    private String errorContext;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
