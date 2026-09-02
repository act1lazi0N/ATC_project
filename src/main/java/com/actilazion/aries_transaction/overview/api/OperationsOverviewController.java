package com.actilazion.aries_transaction.overview.api;

import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.overview.application.OperationsOverviewService;
import com.actilazion.aries_transaction.overview.dto.OperationsOverviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
public class OperationsOverviewController {
    private final OperationsOverviewService service;

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<OperationsOverviewResponse>> getOverview(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(defaultValue = "24h") String range
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Operations overview", service.getOverview(principal.getUserId(), range)));
    }
}
