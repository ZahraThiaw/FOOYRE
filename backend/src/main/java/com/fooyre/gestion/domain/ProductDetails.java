package com.fooyre.gestion.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Informations modifiables d'un produit, déjà validées par le service applicatif. */
public record ProductDetails(
        String name,
        String description,
        String sku,
        String barcode,
        UUID categoryId,
        MeasurementUnit unit,
        BigDecimal purchasePrice,
        BigDecimal salePrice) {
}
