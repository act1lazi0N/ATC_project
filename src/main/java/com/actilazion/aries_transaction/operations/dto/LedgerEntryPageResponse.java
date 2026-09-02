package com.actilazion.aries_transaction.operations.dto;

import java.util.List;

public record LedgerEntryPageResponse(
        List<LedgerEntryResponse> content,
        String nextCursor,
        boolean hasMore
) {
}
