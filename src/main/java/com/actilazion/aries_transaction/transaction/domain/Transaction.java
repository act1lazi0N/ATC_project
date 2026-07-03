package com.actilazion.aries_transaction.transaction.domain;




import com.actilazion.aries_transaction.identity.domain.User;
import com.actilazion.aries_transaction.audit.domain.AuditLog;
import com.actilazion.aries_transaction.account.domain.Account;
import com.actilazion.aries_transaction.transaction.exception.InvalidTransactionStateTransitionException;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "transactions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_transactions_idempotency_key",
        columnNames = "idempotency_key"
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    // Sender account
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_account_id", nullable = false)
    private Account fromAccount;

    // Receiver account
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_account_id", nullable = false)
    private Account toAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by", nullable = false)
    private User initiatedBy;

    // amount > 0 enforced in @Check and DTO validation
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "VND";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
    private String idempotencyKey;

    @Column(length = 255)
    private String description;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<AuditLog> auditLogs = new ArrayList<>();

    public void markCompleted(OffsetDateTime completedAt) {
        if (status == TransactionStatus.COMPLETED) {
            return;
        }
        transitionTo(TransactionStatus.COMPLETED);
        this.completedAt = completedAt;
        this.failureReason = null;
    }

    public void markFailed(String failureReason) {
        if (status == TransactionStatus.FAILED) {
            return;
        }
        transitionTo(TransactionStatus.FAILED);
        this.failureReason = failureReason;
    }

    public void markReversed() {
        transitionTo(TransactionStatus.REVERSED);
    }

    public void markRefunded() {
        transitionTo(TransactionStatus.REFUNDED);
    }

    public void markPartiallyRefunded() {
        transitionTo(TransactionStatus.PARTIALLY_REFUNDED);
    }

    public void setStatus(TransactionStatus status) {
        transitionTo(status);
    }

    private void transitionTo(TransactionStatus targetStatus) {
        Objects.requireNonNull(targetStatus, "Transaction status must not be null");
        if (!status.canTransitionTo(targetStatus)) {
            throw new InvalidTransactionStateTransitionException(status, targetStatus);
        }
        this.status = targetStatus;
    }
}
