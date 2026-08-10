package com.actilazion.aries_transaction.settlement.api;

import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.settlement.application.SettlementService;
import com.actilazion.aries_transaction.settlement.dto.CreateSettlementBatchRequest;
import com.actilazion.aries_transaction.settlement.dto.SettlementBatchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/settlements")
@RequiredArgsConstructor
@Tag(name = "Settlements", description = "Settlement batch operations")
@SecurityRequirement(name = "bearerAuth")
public class SettlementController {
    private final SettlementService settlementService;

    @PostMapping("/batches")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Create a settlement batch from completed unsettled transactions")
    public ResponseEntity<ApiResponse<SettlementBatchResponse>> createBatch(
            @Valid @RequestBody CreateSettlementBatchRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        SettlementBatchResponse response = settlementService.createBatch(
                request.currency(),
                request.feeRateBps(),
                request.idempotencyKey(),
                request.cutoffCompletedAt(),
                userDetails.getUsername()
        );
        return ResponseEntity.ok(ApiResponse.ok("Settlement batch created", response));
    }

    @GetMapping("/batches/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @Operation(summary = "Get settlement batch detail")
    public ResponseEntity<ApiResponse<SettlementBatchResponse>> getBatch(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Settlement batch detail",
                settlementService.getBatch(id, userDetails.getUsername())
        ));
    }
}
