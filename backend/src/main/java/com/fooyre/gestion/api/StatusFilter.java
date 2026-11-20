package com.fooyre.gestion.api;

import com.fooyre.gestion.domain.CatalogStatus;

/** Filtre de statut des listes du catalogue ({@code ACTIVE} par défaut). */
public enum StatusFilter {
    ACTIVE,
    ARCHIVED,
    ALL;

    CatalogStatus toStatus() {
        return switch (this) {
            case ACTIVE -> CatalogStatus.ACTIVE;
            case ARCHIVED -> CatalogStatus.ARCHIVED;
            case ALL -> null;
        };
    }
}
