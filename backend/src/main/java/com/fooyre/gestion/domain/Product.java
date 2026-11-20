package com.fooyre.gestion.domain;

import com.fooyre.shared.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Produit du catalogue d'une entreprise. La catégorie est référencée par identifiant ; la base
 * garantit (FK composite {@code (business_id, category_id)}) qu'elle appartient à la même
 * entreprise. Aucun champ de stock : géré par le futur module dédié (MVP-3).
 */
@Entity
@Table(name = "product")
public class Product extends TenantScopedEntity {

    public static final int MAX_NAME_LENGTH = 150;
    public static final int MAX_DESCRIPTION_LENGTH = 1000;
    public static final int MAX_CODE_LENGTH = 64;

    @Id
    private UUID id;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(length = MAX_DESCRIPTION_LENGTH)
    private String description;

    @Column(length = MAX_CODE_LENGTH)
    private String sku;

    @Column(length = MAX_CODE_LENGTH)
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MeasurementUnit unit;

    /**
     * Prix d'achat de référence (pas le coût moyen pondéré). Règle de saisie : décimales de la devise
     * (ProductService). L'échelle 4 de la colonne n'est qu'une capacité de stockage (valeurs antérieures).
     */
    @Column(name = "purchase_price", precision = 19, scale = 4)
    private BigDecimal purchasePrice;

    @Column(name = "sale_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal salePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CatalogStatus status;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** {@code null} tant que l'entité n'est pas enregistrée : Spring Data la reconnaît comme nouvelle. */
    @Version
    private Integer version;

    protected Product() {
        // JPA
    }

    public Product(ProductDetails details, Instant now) {
        this.id = UUID.randomUUID();
        this.status = CatalogStatus.ACTIVE;
        this.createdAt = now;
        apply(details, now);
    }

    /** Remplace les informations (produit actif uniquement : contrôlé par le service). */
    public void apply(ProductDetails details, Instant now) {
        this.name = CatalogText.normalizeName(details.name());
        this.description = CatalogText.optional(details.description());
        this.sku = CatalogText.optional(details.sku());
        this.barcode = CatalogText.optional(details.barcode());
        this.categoryId = details.categoryId();
        this.unit = details.unit();
        this.purchasePrice = details.purchasePrice();
        this.salePrice = details.salePrice();
        this.updatedAt = now;
    }

    /** @return {@code true} si le produit vient d'être archivé, {@code false} s'il l'était déjà */
    public boolean archive(Instant now) {
        if (status == CatalogStatus.ARCHIVED) {
            return false;
        }
        this.status = CatalogStatus.ARCHIVED;
        this.archivedAt = now;
        this.updatedAt = now;
        return true;
    }

    public boolean isActive() {
        return status == CatalogStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getSku() {
        return sku;
    }

    public String getBarcode() {
        return barcode;
    }

    public MeasurementUnit getUnit() {
        return unit;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public BigDecimal getSalePrice() {
        return salePrice;
    }

    public CatalogStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public int getVersion() {
        return version == null ? 0 : version;
    }
}
