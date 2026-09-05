package com.actilazion.aries_transaction.notification.api;

import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.common.dto.PageResponse;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.notification.application.EmailDeliveryService;
import com.actilazion.aries_transaction.notification.domain.EmailDeliveryStatus;
import com.actilazion.aries_transaction.notification.dto.EmailDeliveryOperationsResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/notification-email-deliveries")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
public class NotificationOperationsController {
    private final EmailDeliveryService service;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<EmailDeliveryOperationsResponse>>> find(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(defaultValue = "DEAD_LETTERED") EmailDeliveryStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        int safeSize = Math.clamp(size, 1, 100);
        var pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return noStore(ApiResponse.ok("Notification email deliveries",
                service.findByStatus(principal.getUserId(), status, pageable)));
    }

    @PostMapping("/{deliveryId}/retry")
    public ResponseEntity<ApiResponse<EmailDeliveryOperationsResponse>> retry(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID deliveryId,
            HttpServletRequest request
    ) {
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(ApiResponse.ok(
                "Notification email delivery queued",
                EmailDeliveryOperationsResponse.from(
                        service.redrive(principal.getUserId(), deliveryId, request.getRemoteAddr()))
        ));
    }

    private <T> ResponseEntity<ApiResponse<T>> noStore(ApiResponse<T> body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
