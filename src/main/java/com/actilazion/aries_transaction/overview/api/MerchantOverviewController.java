package com.actilazion.aries_transaction.overview.api;

import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.overview.application.MerchantOverviewService;
import com.actilazion.aries_transaction.overview.dto.MerchantOverviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchant")
@RequiredArgsConstructor
public class MerchantOverviewController {
    private final MerchantOverviewService service;

    @GetMapping("/overview")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<MerchantOverviewResponse>> getOverview(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(defaultValue = "30d") String range,
            @RequestParam(defaultValue = "UTC") String timezone
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Merchant overview", service.getOverview(principal.getUserId(), range, timezone)));
    }
}
