package com.fooyre.commerce.domain;

import com.fooyre.shared.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Ligne de vente. Brouillon : produit, quantité, remise. Confirmation : photographie historique
 * (nom, SKU, unité, prix, montants, CMP) figée — jamais réécrite ensuite (déclencheur SQL).
 */
@Entity
@Table(name = "sale_line")
public class SaleLine extends TenantScopedEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "sale_id", nullable = false, updatable = false)
    private UUID saleId;

    @Column(nullable = false)
    private int position;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "line_discount", nullable = false, precision = 19, scale = 2)
    private BigDecimal lineDiscount;

    @Column(name = "product_name", length = 150)
    private String productName;

    @Column(name = "product_sku", length = 64)
    private String productSku;

    @Column(length = 20)
    private String unit;

    @Column(name = "unit_price", precision = 19, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "gross_amount", precision = 19, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "global_discount_share", precision = 19, scale = 2)
    private BigDecimal globalDiscountShare;

    @Column(name = "total_excl_tax", precision = 19, scale = 2)
    private BigDecimal totalExclTax;

    @Column(name = "tax_amount", precision = 19, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "total_incl_tax", precision = 19, scale = 2)
    private BigDecimal totalInclTax;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "stock_movement_id")
    private UUID stockMovementId;

    @jakarta.persistence.Transient
    private boolean isNew = true;

    protected SaleLine() {
        // JPA
    }

    public SaleLine(UUID saleId, int position, UUID productId, BigDecimal quantity, BigDecimal lineDiscount) {
        this.id = UUID.randomUUID();
        this.saleId = saleId;
        this.position = position;
        this.productId = productId;
        this.quantity = quantity;
        this.lineDiscount = lineDiscount;
    }

    @jakarta.persistence.PostLoad
    @jakarta.persistence.PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** Photographie figée à la confirmation. */
    public void freeze(String productName, String productSku, String unit, BigDecimal unitPrice,
            TaxCalculator.LineAmounts amounts, BigDecimal unitCost, UUID stockMovementId) {
        this.productName = productName;
        this.productSku = productSku;
        this.unit = unit;
        this.unitPrice = unitPrice;
        this.grossAmount = amounts.gross();
        this.globalDiscountShare = amounts.globalDiscountShare();
        this.totalExclTax = amounts.totalExclTax();
        this.taxAmount = amounts.taxAmount();
        this.totalInclTax = amounts.totalInclTax();
        this.unitCost = unitCost;
        this.stockMovementId = stockMovementId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public int getPosition() {
        return position;
    }

    public UUID getProductId() {
        return productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getLineDiscount() {
        return lineDiscount;
    }

    public String getProductName() {
        return productName;
    }

    public String getProductSku() {
        return productSku;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getGlobalDiscountShare() {
        return globalDiscountShare;
    }

    public BigDecimal getTotalExclTax() {
        return totalExclTax;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getTotalInclTax() {
        return totalInclTax;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }
}
