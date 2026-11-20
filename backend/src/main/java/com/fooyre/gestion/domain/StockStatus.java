package com.fooyre.gestion.domain;

import java.math.BigDecimal;

/**
 * État du stock d'un produit :
 * <ul>
 *   <li>{@code IN_STOCK} : quantité supérieure au seuil minimal ;</li>
 *   <li>{@code LOW_STOCK} : quantité positive inférieure ou égale au seuil (seuil 0 : jamais) ;</li>
 *   <li>{@code OUT_OF_STOCK} : quantité inférieure ou égale à zéro ;</li>
 *   <li>{@code NOT_INITIALIZED} : stock jamais initialisé.</li>
 * </ul>
 */
public enum StockStatus {
    IN_STOCK,
    LOW_STOCK,
    OUT_OF_STOCK,
    NOT_INITIALIZED;

    public static StockStatus of(BigDecimal quantity, BigDecimal minimum) {
        if (quantity == null) {
            return NOT_INITIALIZED;
        }
        if (quantity.signum() <= 0) {
            return OUT_OF_STOCK;
        }
        return quantity.compareTo(minimum) <= 0 ? LOW_STOCK : IN_STOCK;
    }
}
