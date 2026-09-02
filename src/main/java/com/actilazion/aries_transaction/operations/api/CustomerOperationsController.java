package com.actilazion.aries_transaction.operations.api;

import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.common.dto.PageResponse;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.identity.domain.Role;
import com.actilazion.aries_transaction.operations.application.CustomerOperationsService;
import com.actilazion.aries_transaction.operations.dto.CustomerDetailResponse;
import com.actilazion.aries_transaction.operations.dto.CustomerStatus;
import com.actilazion.aries_transaction.operations.dto.CustomerSummaryResponse;
import com.actilazion.aries_transaction.operations.dto.UpdateCustomerStatusRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/customers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
public class CustomerOperationsController {
    private final CustomerOperationsService service;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CustomerSummaryResponse>>> findCustomers(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) CustomerStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction
    ) {
        int safeSize = Math.clamp(size, 1, 100);
        String safeSort = switch (sort) {
            case "fullName", "email", "role", "isActive", "createdAt" -> sort;
            default -> "createdAt";
        };
        Sort.Direction safeDirection = "asc".equalsIgnoreCase(direction)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        var result = service.findCustomers(
                principal.getUserId(), search, role, status,
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by(safeDirection, safeSort).and(Sort.by("id")))
        );
        return ResponseEntity.ok(ApiResponse.ok("Customers", result));
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> getCustomer(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID customerId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Customer detail", service.getCustomer(principal.getUserId(), customerId)));
    }

    @PatchMapping("/{customerId}/status")
    public ResponseEntity<ApiResponse<CustomerSummaryResponse>> updateStatus(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID customerId,
            @Valid @RequestBody UpdateCustomerStatusRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Customer status updated",
                service.updateStatus(principal.getUserId(), customerId, request, servletRequest.getRemoteAddr())
        ));
    }
}
