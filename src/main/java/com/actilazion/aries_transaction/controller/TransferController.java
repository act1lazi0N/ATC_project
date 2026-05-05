package com.actilazion.aries_transaction.controller;

import com.actilazion.aries_transaction.dto.requests.TransferRequest;
import com.actilazion.aries_transaction.dto.responses.ApiResponse;
import com.actilazion.aries_transaction.dto.responses.TransactionResponse;
import com.actilazion.aries_transaction.entity.Transaction;
import com.actilazion.aries_transaction.service.TransferService;
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

    @GetMapping("/{id}")
    @Operation(summary = "Get transaction detail by ID")
    public ResponseEntity<ApiResponse<TransactionResponse>> getById(
            @PathVariable("id") UUID id
    ) {
        return ResponseEntity.ok(ApiResponse.ok("Transaction detail", transferService.getById(id)));
    }

    @GetMapping("/account/{accountId}")
    @Operation(summary = "Get paginated transaction history for an account")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> getByAccount(
            @PathVariable UUID accountId,
            @PageableDefault(size = 20, sort = "createdAt")Pageable pageable
            ) {
        return ResponseEntity.ok(ApiResponse.ok("Transaction history", transferService.getByAccount(accountId, pageable)));
    }
}
