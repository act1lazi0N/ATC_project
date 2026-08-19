package com.actilazion.aries_transaction.identity.domain;

public enum RefreshSessionRevocationReason {
    ROTATED,
    LOGOUT,
    EXPIRED,
    SECURITY_REUSE,
    ADMIN_REVOKED
}
