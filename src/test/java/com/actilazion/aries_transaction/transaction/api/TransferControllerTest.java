package com.actilazion.aries_transaction.transaction.api;

import com.actilazion.aries_transaction.transaction.application.TransferService;
import com.actilazion.aries_transaction.common.redis.DuplicateSuppressionService;
import com.actilazion.aries_transaction.transaction.domain.TransactionStatus;
import com.actilazion.aries_transaction.transaction.dto.RefundRequest;
import com.actilazion.aries_transaction.transaction.dto.ReversalRequest;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferControllerTest {

    private static Validator validator;

    @Mock
    TransferService transferService;

    @Mock
    DuplicateSuppressionService duplicateSuppression;

    @InjectMocks
    TransferController transferController;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @org.junit.jupiter.api.BeforeEach
    void bypassDuplicateSuppressionInUnitTest() {
        lenient().when(duplicateSuppression.execute(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void transfer_validRequest_returnsResponseAndPrincipal() {
        UUID transactionId = UUID.randomUUID();
        UUID fromAccountId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();
        UserDetails principal = principal("sender@test.com");
        TransferRequest request = new TransferRequest(
                fromAccountId.toString(),
                toAccountId.toString(),
                new BigDecimal("1000000"),
                "transfer-key-0001",
                "VND",
                "Test transfer"
        );

        when(transferService.transfer(request, "sender@test.com"))
                .thenReturn(response(transactionId, fromAccountId, toAccountId, "1000000", TransactionStatus.COMPLETED));

        var result = transferController.transfer(request, principal);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().isSuccess()).isTrue();
        assertThat(result.getBody().getMessage()).isEqualTo("Transfer completed successfully");
        assertThat(result.getBody().getData().id()).isEqualTo(transactionId);
        verify(transferService).transfer(request, "sender@test.com");
    }

    @Test
    void transfer_businessInvalidAmount_isLeftForSharedServicePolicy() {
        TransferRequest request = new TransferRequest(
                "",
                UUID.randomUUID().toString(),
                new BigDecimal("999"),
                "short",
                "VND",
                null
        );

        var violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("fromAccountId", "idempotencyKey")
                .doesNotContain("amount");
    }

    @Test
    void refund_positiveAmountBelowTransferMinimum_hasNoAmountViolation() {
        RefundRequest request = new RefundRequest(new BigDecimal("500"), "refund-key-00001", "Remaining refund");

        var violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .doesNotContain("amount");
    }

    @Test
    void refund_zeroAmount_hasValidationViolation() {
        RefundRequest request = new RefundRequest(BigDecimal.ZERO, "refund-key-00001", "Zero refund");

        var violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("amount");
    }

    @Test
    void reverse_validRequest_returnsResponseAndPrincipal() {
        UUID originalTransactionId = UUID.randomUUID();
        UUID reversalTransactionId = UUID.randomUUID();
        UserDetails principal = principal("sender@test.com");
        ReversalRequest request = new ReversalRequest("reverse-key-0001", "Reverse mistake");

        when(transferService.reverse(eq(originalTransactionId), eq(request), eq("sender@test.com")))
                .thenReturn(response(reversalTransactionId, UUID.randomUUID(), UUID.randomUUID(), "1000000", TransactionStatus.COMPLETED));

        var result = transferController.reverse(originalTransactionId, request, principal);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getMessage()).isEqualTo("Transaction reversed successfully");
        assertThat(result.getBody().getData().id()).isEqualTo(reversalTransactionId);
        verify(transferService).reverse(originalTransactionId, request, "sender@test.com");
    }

    @Test
    void refund_validRequest_returnsResponseAndPrincipal() {
        UUID originalTransactionId = UUID.randomUUID();
        UUID refundTransactionId = UUID.randomUUID();
        UserDetails principal = principal("sender@test.com");
        RefundRequest request = new RefundRequest(new BigDecimal("500000"), "refund-key-00001", "Partial refund");

        when(transferService.refund(eq(originalTransactionId), eq(request), eq("sender@test.com")))
                .thenReturn(response(refundTransactionId, UUID.randomUUID(), UUID.randomUUID(), "500000", TransactionStatus.COMPLETED));

        var result = transferController.refund(originalTransactionId, request, principal);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getMessage()).isEqualTo("Transaction refunded successfully");
        assertThat(result.getBody().getData().id()).isEqualTo(refundTransactionId);
        verify(transferService).refund(originalTransactionId, request, "sender@test.com");
    }

    @Test
    void getById_returnsTransactionDetail() {
        UUID transactionId = UUID.randomUUID();
        UUID fromAccountId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();
        UserDetails principal = principal("sender@test.com");

        when(transferService.getById(transactionId, "sender@test.com"))
                .thenReturn(response(transactionId, fromAccountId, toAccountId, "1000000", TransactionStatus.COMPLETED));

        var result = transferController.getById(transactionId, principal);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getMessage()).isEqualTo("Transaction detail");
        assertThat(result.getBody().getData().id()).isEqualTo(transactionId);
        verify(transferService).getById(transactionId, "sender@test.com");
    }

    @Test
    void getByAccount_returnsPagedTransactionHistory() {
        UUID accountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        TransactionResponse transaction = response(
                transactionId,
                accountId,
                UUID.randomUUID(),
                "1000000",
                TransactionStatus.COMPLETED
        );
        PageRequest pageable = PageRequest.of(0, 20);
        UserDetails principal = principal("sender@test.com");

        when(transferService.getByAccount(eq(accountId), any(), eq("sender@test.com")))
                .thenReturn(new PageImpl<>(List.of(transaction), pageable, 1));

        var result = transferController.getByAccount(accountId, pageable, principal);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getMessage()).isEqualTo("Transaction history");
        assertThat(result.getBody().getData().getContent()).extracting(TransactionResponse::id)
                .containsExactly(transactionId);
        verify(transferService).getByAccount(eq(accountId), any(), eq("sender@test.com"));
    }

    private UserDetails principal(String username) {
        return User.withUsername(username)
                .password("unused")
                .roles("USER")
                .build();
    }

    private TransactionResponse response(
            UUID id,
            UUID fromAccountId,
            UUID toAccountId,
            String amount,
            TransactionStatus status
    ) {
        return new TransactionResponse(
                id,
                fromAccountId,
                toAccountId,
                new BigDecimal(amount),
                "VND",
                status,
                "idempotency-key-0001",
                null,
                null,
                null,
                BigDecimal.ZERO,
                OffsetDateTime.parse("2026-07-11T00:00:00+07:00"),
                OffsetDateTime.parse("2026-07-11T00:00:01+07:00")
        );
    }
}
