package com.fooyre.gestion.application;

import com.fooyre.gestion.domain.CatalogStatus;
import com.fooyre.gestion.domain.MeasurementUnit;
import com.fooyre.gestion.domain.StockMovementType;
import com.fooyre.gestion.domain.StockStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Vues renvoyées par le service de stock (aucune entité JPA ne sort de l'application). */
public final class StockViews {

    private StockViews() {
    }

    /** Stock d'un produit ; quantités {@code null} si non initialisé. */
    public record StockItemView(
            UUID productId,
            String productName,
            String sku,
            UUID categoryId,
            String categoryName,
            CatalogStatus categoryStatus,
            MeasurementUnit unit,
            CatalogStatus productStatus,
            BigDecimal quantity,
            BigDecimal minimumQuantity,
            StockStatus status,
            Instant updatedAt,
            /** CMP : {@code null} si inconnu OU sans COST_READ (voir {@code averageCostKnown}). */
            BigDecimal averageCost) {
    }

    public record MovementView(
            UUID id,
            UUID productId,
            String productName,
            MeasurementUnit unit,
            StockMovementType type,
            BigDecimal quantity,
            BigDecimal quantityBefore,
            BigDecimal quantityAfter,
            String reason,
            String reference,
            /** Coût unitaire d'une entrée ; toujours {@code null} sans COST_READ. */
            BigDecimal unitCost,
            UUID authorId,
            String authorName,
            Instant createdAt) {
    }

    /**
     * Résultat d'une opération : mouvement et stock après opération. {@code replayed} : la même
     * demande (même clé d'idempotence, même contenu) avait déjà été traitée — aucun nouveau mouvement.
     */
    /** Ligne de sortie de stock d'une vente (identifiant de ligne = clé d'idempotence du mouvement). */
    public record SaleExitLine(UUID lineId, UUID productId, BigDecimal quantity) {
    }

    /** Résultat : mouvement créé et CMP figé au moment de la sortie ({@code null} = coût inconnu). */
    public record SaleExit(UUID lineId, UUID movementId, BigDecimal unitCost) {
    }

    public record OperationResult(MovementView movement, StockItemView stock, boolean replayed) {
    }

    /**
     * Valeur ESTIMÉE au prix d'achat de RÉFÉRENCE (ce n'est ni le coût réel ni une valeur
     * comptable) : uniquement avec COST_READ.
     */
    public record EstimatedValue(BigDecimal amount, String currency, long productsWithoutReferencePrice) {
    }

    public record SummaryView(
            long trackedProducts,
            long notInitializedProducts,
            long inStock,
            long lowStock,
            long outOfStock,
            EstimatedValue estimatedValue) {
    }
}
