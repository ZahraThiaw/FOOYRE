package com.fooyre.gestion.domain;

import com.fooyre.shared.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Mouvement de stock : événement IMMUABLE, historique métier de référence. Aucune modification ni
 * suppression (entité {@code @Immutable}, déclencheur SQL, aucune politique RLS UPDATE/DELETE) ;
 * une erreur se corrige par un mouvement compensatoire.
 */
@Entity
@Immutable
@Table(name = "stock_movement")
public class StockMovement extends TenantScopedEntity implements Persistable<UUID> {

    public static final int MAX_REASON_LENGTH = 255;
    public static final int MAX_REFERENCE_LENGTH = 100;

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20)
    private StockMovementType type;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "quantity_before", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityBefore;

    @Column(name = "quantity_after", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityAfter;

    @Column(length = MAX_REASON_LENGTH)
    private String reason;

    @Column(length = MAX_REFERENCE_LENGTH)
    private String reference;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    /** Coût unitaire d'acquisition (ENTRY uniquement, facultatif). Jamais exposé sans COST_READ. */
    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /** Toujours une insertion (jamais de fusion) : l'entité n'est créée qu'une fois. */
    @Transient
    private boolean isNew = true;

    protected StockMovement() {
        // JPA
    }

    @PostLoad
    void markLoaded() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public StockMovement(UUID productId, StockMovementType type, BigDecimal quantity, BigDecimal quantityBefore,
            BigDecimal quantityAfter, String reason, String reference, UUID createdBy, Instant now,
            UUID idempotencyKey, String requestHash) {
        this(productId, type, quantity, quantityBefore, quantityAfter, reason, reference, null, createdBy, now,
                idempotencyKey, requestHash);
    }

    /** @param unitCost coût unitaire d'une ENTRÉE (sinon {@code null}) — contrôlé aussi en base. */
    public StockMovement(UUID productId, StockMovementType type, BigDecimal quantity, BigDecimal quantityBefore,
            BigDecimal quantityAfter, String reason, String reference, BigDecimal unitCost, UUID createdBy,
            Instant now, UUID idempotencyKey, String requestHash) {
        if (unitCost != null && (type != StockMovementType.ENTRY || unitCost.signum() <= 0)) {
            throw new IllegalArgumentException("Coût unitaire réservé aux entrées, strictement positif");
        }
        this.unitCost = unitCost;
        this.id = UUID.randomUUID();
        this.productId = Objects.requireNonNull(productId, "productId");
        this.type = Objects.requireNonNull(type, "type");
        this.quantity = Objects.requireNonNull(quantity, "quantity");
        this.quantityBefore = Objects.requireNonNull(quantityBefore, "quantityBefore");
        this.quantityAfter = Objects.requireNonNull(quantityAfter, "quantityAfter");
        this.reason = reason;
        this.reference = reference;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = now;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
    }

    public UUID getId() {
        return id;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public UUID getProductId() {
        return productId;
    }

    public StockMovementType getType() {
        return type;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getQuantityBefore() {
        return quantityBefore;
    }

    public BigDecimal getQuantityAfter() {
        return quantityAfter;
    }

    public String getReason() {
        return reason;
    }

    public String getReference() {
        return reference;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getRequestHash() {
        return requestHash;
    }
}
