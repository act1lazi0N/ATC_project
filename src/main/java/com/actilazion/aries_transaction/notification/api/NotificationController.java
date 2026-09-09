package com.actilazion.aries_transaction.notification.api;

import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.common.dto.PageResponse;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.notification.application.NotificationReadService;
import com.actilazion.aries_transaction.notification.domain.NotificationReadStatus;
import com.actilazion.aries_transaction.notification.dto.MarkAllReadResponse;
import com.actilazion.aries_transaction.notification.dto.NotificationPreferenceResponse;
import com.actilazion.aries_transaction.notification.dto.NotificationResponse;
import com.actilazion.aries_transaction.notification.dto.UnreadCountResponse;
import com.actilazion.aries_transaction.notification.dto.UpdateNotificationPreferenceRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private static final String NO_STORE = "no-store";
    private final NotificationReadService service;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> find(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(defaultValue = "ALL") NotificationReadStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        int safeSize = Math.clamp(size, 1, 100);
        var pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return noStore(ApiResponse.ok("Notifications", service.find(principal.getUserId(), status, pageable)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> unreadCount(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal
    ) {
        return noStore(ApiResponse.ok("Unread notification count", service.unreadCount(principal.getUserId())));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markRead(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID notificationId
    ) {
        return noStore(ApiResponse.ok("Notification marked read",
                service.markRead(principal.getUserId(), notificationId)));
    }

    @PostMapping("/read-all")
    public ResponseEntity<ApiResponse<MarkAllReadResponse>> markAllRead(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal
    ) {
        return noStore(ApiResponse.ok("Notifications marked read", service.markAllRead(principal.getUserId())));
    }

    @GetMapping("/preferences")
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> preferences(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal
    ) {
        return noStore(ApiResponse.ok("Notification preferences", service.preferences(principal.getUserId())));
    }

    @PutMapping("/preferences")
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> updatePreferences(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody UpdateNotificationPreferenceRequest request
    ) {
        return noStore(ApiResponse.ok("Notification preferences updated",
                service.updatePreferences(principal.getUserId(), request)));
    }

    private <T> ResponseEntity<ApiResponse<T>> noStore(ApiResponse<T> body) {
        return ResponseEntity.ok().header("Cache-Control", NO_STORE).body(body);
    }
}
