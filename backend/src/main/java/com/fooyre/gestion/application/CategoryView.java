package com.fooyre.gestion.application;

import com.fooyre.gestion.domain.CatalogStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Catégorie telle que présentée par le service.
 *
 * @param activeProductCount nombre réel de produits ACTIFS rattachés (calculé en base)
 */
public record CategoryView(
        UUID id,
        String name,
        String description,
        CatalogStatus status,
        long activeProductCount,
        Instant createdAt,
        Instant updatedAt,
        int version) {
}
