package com.actilazion.aries_transaction.reconciliation.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationRun {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "window_start", nullable = false)
    private OffsetDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private OffsetDateTime windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReconciliationRunStatus status = ReconciliationRunStatus.RUNNING;

    @Column(name = "source_count", nullable = false)
    private int sourceCount;

    @Column(name = "reporting_count", nullable = false)
    private int reportingCount;

    @Column(name = "exception_count", nullable = false)
    private int exceptionCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @Builder.Default
    private List<ReconciliationException> exceptions = new ArrayList<>();

    public void addException(ReconciliationException exception) {
        exceptions.add(exception);
        exception.setRun(this);
    }

    public void complete(int sourceCount, int reportingCount, OffsetDateTime completedAt) {
        this.sourceCount = sourceCount;
        this.reportingCount = reportingCount;
        this.exceptionCount = exceptions.size();
        this.status = ReconciliationRunStatus.COMPLETED;
        this.completedAt = completedAt;
    }
}
