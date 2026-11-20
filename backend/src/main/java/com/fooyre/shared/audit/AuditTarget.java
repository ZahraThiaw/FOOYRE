package com.fooyre.shared.audit;

/** Types de ressources ciblées par un événement d'audit. */
public enum AuditTarget {
    USER,
    BUSINESS,
    SESSION,
    CATEGORY,
    PRODUCT,
    STOCK_MOVEMENT,
    CUSTOMER,
    SALE
}
