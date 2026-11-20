package com.fooyre.business.domain;

/** Statut d'une entreprise : seule une entreprise {@code ACTIVE} donne accès à ses données. */
public enum BusinessStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED
}
