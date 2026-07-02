package com.actilazion.aries_transaction.audit.domain;

public enum AuditEventType {
    TRANSFER_INITIATED,
    TRANSFER_COMPLETED,
    TRANSFER_FAILED,
    TRANSFER_REVERSED,
    ACCOUNT_FROZEN,
    ACCOUNT_CLOSED
}
