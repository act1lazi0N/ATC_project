package com.actilazion.aries_transaction.notification.dto;

import java.time.OffsetDateTime;

public record MarkAllReadResponse(int updatedCount, OffsetDateTime readThrough) {
}
