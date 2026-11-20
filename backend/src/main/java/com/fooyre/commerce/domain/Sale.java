package com.fooyre.commerce.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Vente. Statut commercial DRAFT → CONFIRMED (ou DRAFT → CANCELLED). Le statut de RÈGLEMENT est
 * calculé à partir des paiements validés (aucun dans ce MVP : Wave non connecté), jamais saisi.
 * Une vente confirmée n'est plus modifiable (contrôle applicatif + déclencheur sur les lignes).
 */
@Entity
@Table(name = "sale")
public class Sale extends TenantScopedEntity {

    @Id
    private UUID id;

    private Long number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SaleStatus status;

    @Column(name = "customer_id")
    private UUID customerId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "global_discount", nullable = false, precision = 19, scale = 2)
    private BigDecimal globalDiscount;

    @Column(name = "vat_enabled")
    private Boolean vatEnabled;

    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Column(name = "price_entry_mode", length = 3)
    private String priceEntryMode;

    @Column(name = "gross_amount", precision = 19, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "discount_amount", precision = 19, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "total_excl_tax", precision = 19, scale = 2)
    private BigDecimal totalExclTax;

    @Column(name = "tax_amount", precision = 19, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "total_incl_tax", precision = 19, scale = 2)
    private BigDecimal totalInclTax;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    private Integer version;

    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    @Column(name = "confirm_idempotency_key")
    private UUID confirmIdempotencyKey;

    protected Sale() {
        // JPA
    }

    public Sale(UUID customerId, String currency, BigDecimal globalDiscount, UUID createdBy, Instant now,
            UUID idempotencyKey, String requestHash) {
        this.id = UUID.randomUUID();
        this.status = SaleStatus.DRAFT;
        this.customerId = customerId;
        this.currency = currency;
        this.globalDiscount = globalDiscount;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    public void updateDraft(UUID customerId, BigDecimal globalDiscount, Instant now) {
        this.customerId = customerId;
        this.globalDiscount = globalDiscount;
        this.updatedAt = now;
    }

    public void confirm(long number, boolean vatEnabled, BigDecimal vatRate, String priceEntryMode,
            BigDecimal gross, BigDecimal discount, BigDecimal ht, BigDecimal tax, BigDecimal ttc, UUID actor,
            UUID confirmKey, Instant now) {
        this.number = number;
        this.status = SaleStatus.CONFIRMED;
        this.vatEnabled = vatEnabled;
        this.vatRate = vatRate;
        this.priceEntryMode = priceEntryMode;
        this.grossAmount = gross;
        this.discountAmount = discount;
        this.totalExclTax = ht;
        this.taxAmount = tax;
        this.totalInclTax = ttc;
        this.confirmedBy = actor;
        this.confirmedAt = now;
        this.confirmIdempotencyKey = confirmKey;
        this.updatedAt = now;
    }

    public void cancelDraft(Instant now) {
        this.status = SaleStatus.CANCELLED;
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    /** Référence affichée (« V-000042 ») ; {@code null} pour un brouillon. */
    public String reference() {
        return number == null ? null : reference(number);
    }

    public static String reference(long number) {
        return String.format("V-%06d", number);
    }

    public UUID getId() {
        return id;
    }

    public Long getNumber() {
        return number;
    }

    public SaleStatus getStatus() {
        return status;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getGlobalDiscount() {
        return globalDiscount;
    }

    public Boolean getVatEnabled() {
        return vatEnabled;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public String getPriceEntryMode() {
        return priceEntryMode;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
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

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public UUID getConfirmedBy() {
        return confirmedBy;
    }

    public int getVersion() {
        return version == null ? 0 : version;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getConfirmIdempotencyKey() {
        return confirmIdempotencyKey;
    }
}
