package com.actilazion.aries_transaction.reconciliation.api;

import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.reconciliation.application.ReconciliationService;
import com.actilazion.aries_transaction.reconciliation.dto.CreateReconciliationRunRequest;
import com.actilazion.aries_transaction.reconciliation.dto.ReconciliationRunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reconciliation")
@RequiredArgsConstructor
@Tag(name = "Reconciliation", description = "Reporting reconciliation operations")
@SecurityRequirement(name = "bearerAuth")
public class ReconciliationController {
    private final ReconciliationService reconciliationService;

    @PostMapping("/runs")
    @Operation(summary = "Run reporting reconciliation for a completed-at window")
    public ResponseEntity<ApiResponse<ReconciliationRunResponse>> createRun(
            @Valid @RequestBody CreateReconciliationRunRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        ReconciliationRunResponse response = reconciliationService.reconcile(
                request.currency(),
                request.windowStart(),
                request.windowEnd(),
                userDetails.getUsername()
        );
        return ResponseEntity.ok(ApiResponse.ok("Reconciliation run completed", response));
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Get reconciliation run detail")
    public ResponseEntity<ApiResponse<ReconciliationRunResponse>> getRun(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Reconciliation run detail",
                reconciliationService.getRun(id, userDetails.getUsername())
        ));
    }
}
