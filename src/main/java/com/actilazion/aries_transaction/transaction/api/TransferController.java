package com.actilazion.aries_transaction.transaction.api;

import com.actilazion.aries_transaction.transaction.dto.RefundRequest;
import com.actilazion.aries_transaction.transaction.dto.ReversalRequest;
import com.actilazion.aries_transaction.transaction.dto.TransferPreviewRequest;
import com.actilazion.aries_transaction.transaction.dto.TransferPreviewResponse;
import com.actilazion.aries_transaction.transaction.dto.TransferExecuteRequest;
import com.actilazion.aries_transaction.transaction.application.TransferPreviewService;
import com.actilazion.aries_transaction.transaction.application.TransferPreviewRequestProtection;
import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.application.TransferService;
import com.actilazion.aries_transaction.common.redis.DuplicateSuppressionService;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers", description = "Internal money transfer operations")
@SecurityRequirement(name = "bearerAuth")
public class TransferController {
    private final TransferService transferService;
    private final DuplicateSuppressionService duplicateSuppression;
    private final TransferPreviewService transferPreviewService;
    private final TransferPreviewRequestProtection previewRequestProtection;

    @PostMapping("/preview")
    @Operation(summary = "Create a transfer preview")
    public ResponseEntity<ApiResponse<TransferPreviewResponse>> preview(
            @Valid @RequestBody TransferPreviewRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        return previewRequestProtection.execute(httpRequest, principal.getUserId(), () ->
                ResponseEntity.ok(ApiResponse.ok("Transfer preview created",
                        transferPreviewService.create(request, principal.getUsername()))));
    }

    @PostMapping
    @Operation(summary = "Execute a previously previewed transfer")
    public ResponseEntity<ApiResponse<TransactionResponse>> execute(
            @Valid @RequestBody TransferExecuteRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        TransactionResponse response = execute(
                "transfer",
                userDetails.getUsername(),
                request.idempotencyKey(),
                request.previewId().toString(),
                () -> transferService.execute(request, userDetails.getUsername())
        );
        return ResponseEntity.ok(ApiResponse.ok("Transfer completed successfully", response));
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Reverse a completed transaction")
    public ResponseEntity<ApiResponse<TransactionResponse>> reverse(
            @PathVariable UUID id,
            @Valid @RequestBody ReversalRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        TransactionResponse response = execute("reverse", userDetails.getUsername(), request.idempotencyKey(), id + ":" + request,
                () -> transferService.reverse(id, request, userDetails.getUsername()));
        return ResponseEntity.ok(ApiResponse.ok("Transaction reversed successfully", response));
    }

    @PostMapping("/{id}/refund")
    @Operation(summary = "Refund a completed transaction")
    public ResponseEntity<ApiResponse<TransactionResponse>> refund(
            @PathVariable UUID id,
            @Valid @RequestBody RefundRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        TransactionResponse response = execute("refund", userDetails.getUsername(), request.idempotencyKey(), id + ":" + request,
                () -> transferService.refund(id, request, userDetails.getUsername()));
        return ResponseEntity.ok(ApiResponse.ok("Transaction refunded successfully", response));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get transaction detail by ID")
    public ResponseEntity<ApiResponse<TransactionResponse>> getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Transaction detail",
                transferService.getById(id, userDetails.getUsername())
        ));
    }

    @GetMapping("/account/{accountId}")
    @Operation(summary = "Get paginated transaction history for an account")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> getByAccount(
            @PathVariable UUID accountId,
            @PageableDefault(size = 20, sort = "createdAt")Pageable pageable,
            @AuthenticationPrincipal UserDetails userDetails
            ) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Transaction history",
                transferService.getByAccount(accountId, pageable, userDetails.getUsername())
        ));
    }

    private <T> T execute(String op, String owner, String key, String fingerprint, java.util.function.Supplier<T> action) {
        return duplicateSuppression.execute(op, owner, key, fingerprint, action);
    }
}
