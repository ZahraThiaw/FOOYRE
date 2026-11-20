package com.fooyre.business.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Entreprise (le tenant). Paramètres financiers et formule : lots suivants. */
@Entity
@Table(name = "business")
public class Business {

    public static final int MAX_NAME_LENGTH = 150;

    @Id
    private UUID id;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(name = "legal_name", length = 200)
    private String legalName;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Column(name = "default_locale", nullable = false, length = 10)
    private String defaultLocale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BusinessStatus status;

    /** Stock négatif autorisé (D5) : désactivé par défaut, modifiable par le propriétaire seul (D20). */
    @Column(name = "allow_negative_stock", nullable = false)
    private boolean allowNegativeStock;

    /** TVA (D4) : désactivée par défaut ; taux et mode de saisie modifiables par le propriétaire seul. */
    @Column(name = "vat_enabled", nullable = false)
    private boolean vatEnabled;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRate = new BigDecimal("18.00");

    @Column(name = "price_entry_mode", nullable = false, length = 3)
    private String priceEntryMode = "TTC";

    /** Remise maximale de l'EMPLOYÉ, en % du montant brut de la vente (D14 ; forme provisoire O11). */
    @Column(name = "employee_max_discount_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal employeeMaxDiscountRate = BigDecimal.ZERO;

    /** IA (D15) : désactivée par défaut ; activée uniquement par consentement explicite du propriétaire. */
    @Column(name = "ai_enabled", nullable = false)
    private boolean aiEnabled;

    @Column(name = "ai_consented_at")
    private Instant aiConsentedAt;

    @Column(name = "ai_consented_by")
    private UUID aiConsentedBy;

    @Column(name = "ai_terms_version", length = 20)
    private String aiTermsVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected Business() {
        // JPA
    }

    public Business(String name, String currency, String timezone, String defaultLocale, Instant now) {
        this.id = UUID.randomUUID();
        this.name = requireText(name, "name");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.timezone = Objects.requireNonNull(timezone, "timezone");
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
        this.status = BusinessStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " obligatoire");
        }
        return value.trim();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getCurrency() {
        return currency;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public BusinessStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == BusinessStatus.ACTIVE;
    }

    public boolean isAllowNegativeStock() {
        return allowNegativeStock;
    }

    /** @return {@code true} si la valeur a changé */
    public boolean changeAllowNegativeStock(boolean allowed, Instant now) {
        if (allowNegativeStock == allowed) {
            return false;
        }
        this.allowNegativeStock = allowed;
        this.updatedAt = now;
        return true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isVatEnabled() {
        return vatEnabled;
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public String getPriceEntryMode() {
        return priceEntryMode;
    }

    public BigDecimal getEmployeeMaxDiscountRate() {
        return employeeMaxDiscountRate;
    }

    /** @return {@code true} si un paramètre fiscal a changé */
    public boolean changeTax(boolean enabled, BigDecimal rate, String mode, Instant now) {
        if (vatEnabled == enabled && vatRate.compareTo(rate) == 0 && priceEntryMode.equals(mode)) {
            return false;
        }
        this.vatEnabled = enabled;
        this.vatRate = rate;
        this.priceEntryMode = mode;
        this.updatedAt = now;
        return true;
    }

    /** @return {@code true} si la limite a changé */
    public boolean changeEmployeeMaxDiscountRate(BigDecimal rate, Instant now) {
        if (employeeMaxDiscountRate.compareTo(rate) == 0) {
            return false;
        }
        this.employeeMaxDiscountRate = rate;
        this.updatedAt = now;
        return true;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    public Instant getAiConsentedAt() {
        return aiConsentedAt;
    }

    public UUID getAiConsentedBy() {
        return aiConsentedBy;
    }

    public String getAiTermsVersion() {
        return aiTermsVersion;
    }

    /** Consentement explicite (enabled) ou révocation ; @return {@code true} si l'état a changé */
    public boolean changeAiConsent(boolean enabled, UUID ownerId, String termsVersion, Instant now) {
        if (enabled == aiEnabled) {
            return false;
        }
        this.aiEnabled = enabled;
        if (enabled) {
            this.aiConsentedAt = now;
            this.aiConsentedBy = ownerId;
            this.aiTermsVersion = termsVersion;
        }
        this.updatedAt = now;
        return true;
    }
}
