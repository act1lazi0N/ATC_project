package com.actilazion.aries_transaction.transaction.api;

import com.actilazion.aries_transaction.transaction.dto.RefundRequest;
import com.actilazion.aries_transaction.transaction.dto.ReversalRequest;
import com.actilazion.aries_transaction.transaction.dto.TransferRequest;
import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.transaction.dto.TransactionResponse;
import com.actilazion.aries_transaction.transaction.application.TransferService;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers", description = "Internal money transfer operations")
@SecurityRequirement(name = "bearerAuth")
public class TransferController {
    private final TransferService transferService;

    @PostMapping
    @Operation(summary = "Initiate a transfer between two accounts")
    public ResponseEntity<ApiResponse<TransactionResponse>> transfer(
            @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        TransactionResponse response = transferService.transfer(request, userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("Transfer completed successfully", response));
    }

    @PostMapping("/{id}/reverse")
    @Operation(summary = "Reverse a completed transaction")
    public ResponseEntity<ApiResponse<TransactionResponse>> reverse(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ReversalRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        TransactionResponse response = transferService.reverse(id, request, userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("Transaction reversed successfully", response));
    }

    @PostMapping("/{id}/refund")
    @Operation(summary = "Refund a completed transaction")
    public ResponseEntity<ApiResponse<TransactionResponse>> refund(
            @PathVariable("id") UUID id,
            @Valid @RequestBody RefundRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        TransactionResponse response = transferService.refund(id, request, userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("Transaction refunded successfully", response));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get transaction detail by ID")
    public ResponseEntity<ApiResponse<TransactionResponse>> getById(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ResponseEntity.ok(ApiResponse.ok("Transaction detail", transferService.getById(id, userDetails.getUsername())));
    }

    @GetMapping("/account/{accountId}")
    @Operation(summary = "Get paginated transaction history for an account")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> getByAccount(
            @PathVariable UUID accountId,
            @PageableDefault(size = 20, sort = "createdAt")Pageable pageable,
            @AuthenticationPrincipal UserDetails userDetails
            ) {
        return ResponseEntity.ok(ApiResponse.ok("Transaction history", transferService.getByAccount(accountId, pageable, userDetails.getUsername())));
    }
}
