package com.actilazion.aries_transaction.transaction;

import com.actilazion.aries_transaction.transaction.domain.Transaction;
import com.actilazion.aries_transaction.transaction.domain.TransactionStateGuard;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.domain.exception.InvalidTransactionStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionStateGuardTest {

    @Test
    @DisplayName("State guard allows valid lifecycle transitions")
    void canTransition_validTransitions_returnsTrue() {
        assertThat(TransactionStateGuard.canTransition(TransactionStatus.PENDING, TransactionStatus.COMPLETED)).isTrue();
        assertThat(TransactionStateGuard.canTransition(TransactionStatus.PENDING, TransactionStatus.FAILED)).isTrue();
        assertThat(TransactionStateGuard.canTransition(TransactionStatus.COMPLETED, TransactionStatus.REVERSED)).isTrue();
        assertThat(TransactionStateGuard.canTransition(TransactionStatus.COMPLETED, TransactionStatus.REFUNDED)).isTrue();
        assertThat(TransactionStateGuard.canTransition(TransactionStatus.COMPLETED, TransactionStatus.PARTIALLY_REFUNDED)).isTrue();
        assertThat(TransactionStateGuard.canTransition(TransactionStatus.PARTIALLY_REFUNDED, TransactionStatus.REFUNDED)).isTrue();
    }

    @Test
    @DisplayName("State guard rejects invalid lifecycle transitions")
    void assertCanTransition_invalidTransitions_throwsException() {
        assertThatThrownBy(() -> TransactionStateGuard.assertCanTransition(TransactionStatus.FAILED, TransactionStatus.COMPLETED))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: FAILED -> COMPLETED");
        assertThatThrownBy(() -> TransactionStateGuard.assertCanTransition(TransactionStatus.PENDING, TransactionStatus.REVERSED))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: PENDING -> REVERSED");
        assertThatThrownBy(() -> TransactionStateGuard.assertCanTransition(TransactionStatus.REVERSED, TransactionStatus.COMPLETED))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: REVERSED -> COMPLETED");
        assertThatThrownBy(() -> TransactionStateGuard.assertCanTransition(TransactionStatus.REFUNDED, TransactionStatus.COMPLETED))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: REFUNDED -> COMPLETED");
        assertThatThrownBy(() -> TransactionStateGuard.assertCanTransition(TransactionStatus.REVERSED, TransactionStatus.REVERSED))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: REVERSED -> REVERSED");
    }

    @Test
    @DisplayName("Only completed transaction can be reversed")
    void assertCanReverse_nonCompleted_throwsException() {
        Transaction pending = Transaction.builder()
                .status(TransactionStatus.PENDING)
                .build();
        Transaction reversed = Transaction.builder()
                .status(TransactionStatus.REVERSED)
                .build();

        assertThatThrownBy(() -> TransactionStateGuard.assertCanReverse(pending))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: PENDING -> REVERSED");
        assertThatThrownBy(() -> TransactionStateGuard.assertCanReverse(reversed))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: REVERSED -> REVERSED");
    }

    @Test
    @DisplayName("Completed and partially refunded transactions can be refunded")
    void assertCanRefund_refundableStates_success() {
        Transaction completed = Transaction.builder()
                .status(TransactionStatus.COMPLETED)
                .build();
        Transaction partiallyRefunded = Transaction.builder()
                .status(TransactionStatus.PARTIALLY_REFUNDED)
                .build();

        TransactionStateGuard.assertCanRefund(completed);
        TransactionStateGuard.assertCanRefund(partiallyRefunded);
    }

    @Test
    @DisplayName("Non-refundable states cannot be refunded")
    void assertCanRefund_nonRefundableStates_throwsException() {
        Transaction pending = Transaction.builder()
                .status(TransactionStatus.PENDING)
                .build();
        Transaction failed = Transaction.builder()
                .status(TransactionStatus.FAILED)
                .build();
        Transaction reversed = Transaction.builder()
                .status(TransactionStatus.REVERSED)
                .build();
        Transaction refunded = Transaction.builder()
                .status(TransactionStatus.REFUNDED)
                .build();

        assertThatThrownBy(() -> TransactionStateGuard.assertCanRefund(pending))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: PENDING -> REFUNDED");
        assertThatThrownBy(() -> TransactionStateGuard.assertCanRefund(failed))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: FAILED -> REFUNDED");
        assertThatThrownBy(() -> TransactionStateGuard.assertCanRefund(reversed))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: REVERSED -> REFUNDED");
        assertThatThrownBy(() -> TransactionStateGuard.assertCanRefund(refunded))
                .isInstanceOf(InvalidTransactionStateTransitionException.class)
                .hasMessage("Invalid transaction state transition: REFUNDED -> REFUNDED");
    }
}
