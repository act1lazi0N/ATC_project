package com.actilazion.aries_transaction.payment.api;

import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.payment.application.*;
import com.actilazion.aries_transaction.payment.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Payment QR", description = "Internal Aries account and single-payment QR codes")
@SecurityRequirement(name = "bearerAuth")
public class PaymentQrController {
    private final PaymentQrService service;
    private final QrRequestProtection protection;

    @PostMapping("/accounts/{accountId}/qr-codes")
    @Operation(summary = "Create an owner QR code with an Idempotency-Key")
    public ResponseEntity<ApiResponse<QrResponse>> create(@PathVariable UUID accountId,
            @Valid @RequestBody CreateQrRequest request, @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal, HttpServletRequest http) {
        protection.check(http, principal.getUserId());
        return response("QR code created", service.create(accountId, request, key, principal.getUserId()));
    }

    @GetMapping("/accounts/{accountId}/qr-codes")
    @Operation(summary = "List QR codes for an owned account")
    public ResponseEntity<ApiResponse<Page<QrResponse>>> list(@PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal, HttpServletRequest http) {
        protection.check(http, principal.getUserId());
        return response("QR codes", service.list(accountId, principal.getUserId(), page, size));
    }

    @PostMapping("/qr-codes/{id}/revoke")
    @Operation(summary = "Revoke an owned QR code")
    public ResponseEntity<ApiResponse<QrResponse>> revoke(@PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal, HttpServletRequest http) {
        protection.check(http, principal.getUserId());
        return response("QR code revoked", service.revoke(id, principal.getUserId()));
    }

    @PostMapping("/qr-codes/resolve")
    @Operation(summary = "Resolve an Aries v1 payload to masked recipient and payment details")
    public ResponseEntity<ApiResponse<ResolvedQrResponse>> resolve(@Valid @RequestBody ResolveQrRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal, HttpServletRequest http) {
        protection.check(http, principal.getUserId());
        return response("QR code resolved", service.resolve(request.payload(), principal.getUserId()));
    }

    private <T> ResponseEntity<ApiResponse<T>> response(String message, T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.ok(message, body));
    }
}
