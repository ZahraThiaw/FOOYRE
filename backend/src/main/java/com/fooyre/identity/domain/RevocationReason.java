package com.fooyre.identity.domain;

/** Motif de révocation d'une session (docs/database-schema.md §3.1, ADR-04). */
public enum RevocationReason {
    LOGOUT,
    LOGOUT_ALL,
    PASSWORD_CHANGED,
    REUSE_DETECTED,
    MEMBERSHIP_REVOKED,
    ACCOUNT_DISABLED,
    BUSINESS_SUSPENDED
}
