package com.actilazion.aries_transaction.operations.api;

import com.actilazion.aries_transaction.common.dto.ApiResponse;
import com.actilazion.aries_transaction.identity.application.AuthenticatedUserPrincipal;
import com.actilazion.aries_transaction.ledger.domain.LedgerDirection;
import com.actilazion.aries_transaction.ledger.domain.LedgerEntryType;
import com.actilazion.aries_transaction.operations.application.OperationsLedgerService;
import com.actilazion.aries_transaction.operations.dto.LedgerEntryPageResponse;
import com.actilazion.aries_transaction.operations.dto.LedgerJournalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
public class OperationsLedgerController {
    private final OperationsLedgerService service;

    @GetMapping("/ledger-entries")
    public ResponseEntity<ApiResponse<LedgerEntryPageResponse>> findEntries(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) UUID transactionId,
            @RequestParam(required = false) LedgerEntryType entryType,
            @RequestParam(required = false) LedgerDirection direction,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Ledger entries",
                service.findEntries(principal.getUserId(), from, to, transactionId, entryType, direction, currency, cursor, limit)
        ));
    }

    @GetMapping("/ledger-journals/{transactionId}")
    public ResponseEntity<ApiResponse<LedgerJournalResponse>> getJournal(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID transactionId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Ledger journal", service.getJournal(principal.getUserId(), transactionId)));
    }
}
