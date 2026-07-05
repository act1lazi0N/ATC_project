package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.exception.InvalidTransactionStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionStateTransitionTest {

    @Test
    @DisplayName("PENDING transaction can be completed")
    void pendingToCompleted_success() {
        Transaction tx = Transaction.builder()
                .status(TransactionStatus.PENDING)
                .build();
        OffsetDateTime completedAt = OffsetDateTime.now();

        tx.markCompleted(completedAt);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.getCompletedAt()).isEqualTo(completedAt);
        assertThat(tx.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("FAILED transaction cannot become COMPLETED")
    void failedToCompleted_throwsException() {
        Transaction tx = Transaction.builder()
                .status(TransactionStatus.FAILED)
                .build();

        assertThatThrownBy(() -> tx.markCompleted(OffsetDateTime.now()))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: FAILED -> COMPLETED");
    }

    @Test
    @DisplayName("PENDING transaction cannot be reversed")
    void pendingToReversed_throwsException() {
        Transaction tx = Transaction.builder()
                .status(TransactionStatus.PENDING)
                .build();

        assertThatThrownBy(tx::markReversed)
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: PENDING -> REVERSED");
    }

    @Test
    @DisplayName("Terminal refund states cannot become COMPLETED")
    void terminalRefundStatesToCompleted_throwException() {
        Transaction refunded = Transaction.builder()
                .status(TransactionStatus.REFUNDED)
                .build();
        Transaction partiallyRefunded = Transaction.builder()
                .status(TransactionStatus.PARTIALLY_REFUNDED)
                .build();

        assertThatThrownBy(() -> refunded.markCompleted(OffsetDateTime.now()))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: REFUNDED -> COMPLETED");
        assertThatThrownBy(() -> partiallyRefunded.markCompleted(OffsetDateTime.now()))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: PARTIALLY_REFUNDED -> COMPLETED");
    }

    @Test
    @DisplayName("COMPLETED transaction can become reversed, refunded, or partially refunded")
    void completedToPostCompletionStates_success() {
        Transaction reversed = Transaction.builder()
                .status(TransactionStatus.COMPLETED)
                .build();
        Transaction refunded = Transaction.builder()
                .status(TransactionStatus.COMPLETED)
                .build();
        Transaction partiallyRefunded = Transaction.builder()
                .status(TransactionStatus.COMPLETED)
                .build();

        reversed.markReversed();
        refunded.markRefunded();
        partiallyRefunded.markPartiallyRefunded();

        assertThat(reversed.getStatus()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(refunded.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        assertThat(partiallyRefunded.getStatus()).isEqualTo(TransactionStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("PARTIALLY_REFUNDED transaction can become REFUNDED")
    void partiallyRefundedToRefunded_success() {
        Transaction tx = Transaction.builder()
                .status(TransactionStatus.PARTIALLY_REFUNDED)
                .build();

        tx.markRefunded();

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
    }
}
