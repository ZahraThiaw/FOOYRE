package com.fooyre.gestion.domain;

import com.fooyre.shared.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * État courant du stock d'un produit (projection du journal {@link StockMovement}). Son existence
 * signifie « stock initialisé ». La quantité n'est modifiée QUE par le service de stock, sous
 * verrou ({@code SELECT … FOR UPDATE}), dans la transaction qui enregistre le mouvement.
 */
@Entity
@Table(name = "product_stock")
public class ProductStock extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "minimum_quantity", nullable = false, precision = 19, scale = 3)
    private BigDecimal minimumQuantity;

    /**
     * Coût moyen pondéré courant (D1), 4 décimales ; {@code null} = coût INCONNU (jamais déduit du
     * prix d'achat de référence). Modifié uniquement par une entrée, sous le verrou du stock.
     */
    @Column(name = "average_cost", precision = 19, scale = 4)
    private BigDecimal averageCost;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** {@code null} tant que l'entité n'est pas enregistrée : Spring Data la reconnaît comme nouvelle. */
    @Version
    private Integer version;

    protected ProductStock() {
        // JPA
    }

    public ProductStock(UUID productId, BigDecimal initialQuantity, BigDecimal minimumQuantity, Instant now) {
        this.id = UUID.randomUUID();
        this.productId = Objects.requireNonNull(productId, "productId");
        this.quantity = Objects.requireNonNull(initialQuantity, "initialQuantity");
        this.minimumQuantity = Objects.requireNonNull(minimumQuantity, "minimumQuantity");
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Nouvelle quantité, calculée et contrôlée par le service (jamais sans mouvement). */
    public void applyQuantity(BigDecimal newQuantity, Instant now) {
        this.quantity = Objects.requireNonNull(newQuantity, "newQuantity");
        this.updatedAt = now;
    }

    /** @return {@code true} si le seuil a changé */
    public boolean changeMinimum(BigDecimal minimum, Instant now) {
        if (minimumQuantity.compareTo(minimum) == 0) {
            return false;
        }
        this.minimumQuantity = minimum;
        this.updatedAt = now;
        return true;
    }

    public StockStatus status() {
        return StockStatus.of(quantity, minimumQuantity);
    }

    public BigDecimal getAverageCost() {
        return averageCost;
    }

    /**
     * Met à jour le CMP pour une ENTRÉE de {@code quantityIn} unités (à appeler AVANT
     * {@link #applyQuantity}). Règles :
     * <ul>
     *   <li>entrée SANS coût : le stock contient désormais des unités de coût inconnu → CMP inconnu ;</li>
     *   <li>entrée avec coût sur un stock nul ou négatif : CMP = coût de l'entrée ;</li>
     *   <li>entrée avec coût sur un stock positif de CMP connu : moyenne pondérée, arrondie à 4 décimales ;</li>
     *   <li>entrée avec coût sur un stock positif de CMP INCONNU : reste inconnu (aucun coût inventé).</li>
     * </ul>
     * Sorties et ajustements ne modifient jamais le CMP.
     */
    public void applyEntryCost(BigDecimal quantityIn, BigDecimal unitCost) {
        BigDecimal before = quantity;
        if (unitCost == null) {
            this.averageCost = null;
        } else if (before.signum() <= 0) {
            this.averageCost = unitCost.setScale(4, java.math.RoundingMode.HALF_UP);
        } else if (averageCost != null) {
            BigDecimal value = before.multiply(averageCost).add(quantityIn.multiply(unitCost));
            this.averageCost = value.divide(before.add(quantityIn), 4, java.math.RoundingMode.HALF_UP);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getMinimumQuantity() {
        return minimumQuantity;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
