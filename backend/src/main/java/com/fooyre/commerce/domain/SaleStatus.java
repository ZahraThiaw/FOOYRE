package com.fooyre.commerce.domain;

/** Statut COMMERCIAL d'une vente (distinct du statut de règlement, calculé). */
public enum SaleStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED
}
