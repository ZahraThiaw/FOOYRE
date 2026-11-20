package com.fooyre.gestion.application;

import com.fooyre.gestion.domain.CatalogStatus;
import com.fooyre.gestion.domain.MeasurementUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Produit tel que présenté par le service.
 *
 * @param purchasePrice prix d'achat de référence ; {@code null} si absent OU si l'utilisateur n'a
 *                      pas la permission {@code COST_READ} ({@code costVisible} distingue les deux)
 * @param costVisible   l'utilisateur a le droit de voir les coûts
 */
public record ProductView(
        UUID id,
        String name,
        String description,
        String sku,
        String barcode,
        MeasurementUnit unit,
        UUID categoryId,
        String categoryName,
        CatalogStatus categoryStatus,
        BigDecimal purchasePrice,
        boolean costVisible,
        BigDecimal salePrice,
        String currency,
        CatalogStatus status,
        Instant createdAt,
        Instant updatedAt,
        int version) {
}
