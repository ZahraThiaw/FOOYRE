package com.fooyre.gestion.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Vue d'ensemble du stock : produits ACTIFS de l'entreprise avec leur stock éventuel (jointure
 * externe), recherche, filtres et tri paginés côté base. Implémentée dans l'infrastructure
 * (requête paramétrée). L'entreprise est un critère obligatoire (en plus de la RLS).
 */
public interface StockOverviewRepository {

    /** Filtre d'état ({@code null} : tous). {@code ALERT} = stock faible ou rupture. */
    enum StatusFilter { IN_STOCK, LOW_STOCK, OUT_OF_STOCK, NOT_INITIALIZED, TRACKED, ALERT }

    record Criteria(UUID businessId, String text, UUID categoryId, StatusFilter status) {
    }

    /** Ligne : produit et stock ({@code null} si non initialisé). */
    record StockLine(Product product, ProductStock stock) {
    }

    /** Compteurs des produits actifs. */
    record Counts(long activeProducts, long tracked, long inStock, long lowStock, long outOfStock) {
    }

    /** Quantité positive et prix d'achat de référence (éventuellement absent) d'un produit suivi. */
    record ValuationLine(BigDecimal quantity, BigDecimal referencePurchasePrice) {
    }

    Page<StockLine> search(Criteria criteria, Pageable pageable);

    Counts counts(UUID businessId);

    List<ValuationLine> positiveStockValuationLines(UUID businessId);
}
