package com.actilazion.aries_transaction.audit.domain;

public enum IdentityAuditEventType {
    REGISTERED,
    REGISTRATION_REJECTED,
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    LOGIN_LOCKED,
    REFRESH_SUCCEEDED,
    REFRESH_REJECTED,
    REFRESH_REUSE_DETECTED,
    LOGOUT
}
