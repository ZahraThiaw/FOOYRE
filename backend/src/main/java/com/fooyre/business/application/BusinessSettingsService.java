package com.fooyre.business.application;

import com.fooyre.shared.error.FieldErrorCode;
import com.fooyre.shared.error.FieldValidationException;
import java.math.BigDecimal;

import com.fooyre.business.domain.Business;
import com.fooyre.business.domain.BusinessRepository;
import com.fooyre.shared.audit.AuditAction;
import com.fooyre.shared.audit.AuditDetail;
import com.fooyre.shared.audit.AuditEvent;
import com.fooyre.shared.audit.AuditOutcome;
import com.fooyre.shared.audit.AuditService;
import com.fooyre.shared.audit.AuditTarget;
import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import com.fooyre.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paramètres sensibles de l'entreprise ACTIVE (D20 : lecture OWNER/MANAGER, modification OWNER —
 * contrôlé par les permissions de l'API). MVP-3 : stock négatif autorisé (D5, désactivé par
 * défaut). L'entreprise vient uniquement de {@link TenantContext}.
 */
@Service
public class BusinessSettingsService {

    public record BusinessSettingsView(boolean allowNegativeStock, boolean vatEnabled, BigDecimal vatRate,
            String priceEntryMode, BigDecimal employeeMaxDiscountRate) {
    }

    /** Paramètres utiles à la vente, lus dans la transaction de l'appelant (aucun contrôle de droit : usage interne). */
    public record SalesSettings(boolean vatEnabled, BigDecimal vatRate, String priceEntryMode,
            BigDecimal employeeMaxDiscountRate) {
    }

    /** Modification partielle : un champ {@code null} reste inchangé. */
    public record SettingsCommand(Boolean allowNegativeStock, Boolean vatEnabled, BigDecimal vatRate,
            String priceEntryMode) {
    }

    /** État du consentement IA (D15). */
    public record AiConsentView(boolean enabled, java.time.Instant consentedAt, UUID consentedBy, String termsVersion) {
    }

    private final BusinessRepository businesses;
    private final AuditService audit;
    private final Clock clock;

    BusinessSettingsService(BusinessRepository businesses, AuditService audit, Clock clock) {
        this.businesses = businesses;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BusinessSettingsView activeSettings() {
        return view(activeBusiness());
    }

    @Transactional(readOnly = true)
    public SalesSettings salesSettings() {
        Business business = activeBusiness();
        return new SalesSettings(business.isVatEnabled(), business.getVatRate(), business.getPriceEntryMode(),
                business.getEmployeeMaxDiscountRate());
    }

    /** Paramètres sensibles (propriétaire seul : contrôlé par l'API) ; chaque changement est journalisé. */
    @Transactional
    public BusinessSettingsView update(SettingsCommand command) {
        if (command.allowNegativeStock() != null) {
            updateAllowNegativeStock(command.allowNegativeStock());
        }
        Business business = activeBusiness();
        if (command.vatEnabled() != null || command.vatRate() != null || command.priceEntryMode() != null) {
            boolean enabled = command.vatEnabled() != null ? command.vatEnabled() : business.isVatEnabled();
            BigDecimal rate = command.vatRate() != null ? command.vatRate() : business.getVatRate();
            String mode = command.priceEntryMode() != null ? command.priceEntryMode() : business.getPriceEntryMode();
            if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0 || rate.stripTrailingZeros().scale() > 2) {
                throw FieldValidationException.of("vatRate", FieldErrorCode.INVALID_VALUE);
            }
            if (!"HT".equals(mode) && !"TTC".equals(mode)) {
                throw FieldValidationException.of("priceEntryMode", FieldErrorCode.INVALID_VALUE);
            }
            if (business.changeTax(enabled, rate, mode, clock.instant())) {
                businesses.saveAndFlush(business);
                audit.record(settingsEvent(business, "TAX_SETTINGS_CHANGED"));
            }
        }
        return view(business);
    }

    /** Limite de remise des employés (SALES_POLICY_MANAGE : OWNER, MANAGER — D14). */
    @Transactional
    public BusinessSettingsView updateEmployeeMaxDiscountRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0
                || rate.stripTrailingZeros().scale() > 2) {
            throw FieldValidationException.of("employeeMaxDiscountRate", FieldErrorCode.INVALID_VALUE);
        }
        Business business = activeBusiness();
        if (business.changeEmployeeMaxDiscountRate(rate, clock.instant())) {
            businesses.saveAndFlush(business);
            audit.record(AuditEvent.of(AuditAction.SALES_POLICY_CHANGED, AuditOutcome.SUCCESS)
                    .actor(TenantContext.requireUser().userId())
                    .business(business.getId())
                    .target(AuditTarget.BUSINESS, business.getId()));
        }
        return view(business);
    }

    @Transactional(readOnly = true)
    public AiConsentView aiConsent() {
        Business business = activeBusiness();
        return new AiConsentView(business.isAiEnabled(), business.getAiConsentedAt(), business.getAiConsentedBy(),
                business.getAiTermsVersion());
    }

    /** Consentement explicite du propriétaire ou révocation (effet immédiat), journalisé (D15). */
    @Transactional
    public AiConsentView changeAiConsent(boolean enabled, String termsVersion) {
        Business business = activeBusiness();
        if (enabled && (termsVersion == null || termsVersion.isBlank())) {
            throw FieldValidationException.of("termsVersion", FieldErrorCode.REQUIRED);
        }
        UUID actor = TenantContext.requireUser().userId();
        if (business.changeAiConsent(enabled, actor, termsVersion, clock.instant())) {
            businesses.saveAndFlush(business);
            audit.record(AuditEvent.of(AuditAction.AI_CONSENT_CHANGED, AuditOutcome.SUCCESS)
                    .actor(actor)
                    .business(business.getId())
                    .target(AuditTarget.BUSINESS, business.getId())
                    .with(AuditDetail.REASON, enabled ? "AI_ENABLED" : "AI_REVOKED"));
        }
        return aiConsent();
    }

    /** Consentement IA EN VIGUEUR, relu en base à chaque requête de l'assistante. */
    @Transactional(readOnly = true)
    public boolean aiEnabledForActiveBusiness() {
        return activeBusiness().isAiEnabled();
    }

    private AuditEvent settingsEvent(Business business, String reason) {
        return AuditEvent.of(AuditAction.SETTINGS_CHANGED, AuditOutcome.SUCCESS)
                .actor(TenantContext.requireUser().userId())
                .business(business.getId())
                .target(AuditTarget.BUSINESS, business.getId())
                .with(AuditDetail.REASON, reason);
    }

    private static BusinessSettingsView view(Business business) {
        return new BusinessSettingsView(business.isAllowNegativeStock(), business.isVatEnabled(), business.getVatRate(),
                business.getPriceEntryMode(), business.getEmployeeMaxDiscountRate());
    }

    /**
     * Valeur COURANTE du paramètre, relue en base dans la transaction de l'appelant (opération de
     * stock) : un changement validé est pris en compte dès l'opération suivante.
     */
    @Transactional(readOnly = true)
    public boolean allowsNegativeStock() {
        return activeBusiness().isAllowNegativeStock();
    }

    /** Modifie le paramètre ; journalisé uniquement s'il change réellement. */
    @Transactional
    public BusinessSettingsView updateAllowNegativeStock(boolean allowed) {
        Business business = activeBusiness();
        if (business.changeAllowNegativeStock(allowed, clock.instant())) {
            businesses.saveAndFlush(business);
            audit.record(AuditEvent.of(AuditAction.SETTINGS_CHANGED, AuditOutcome.SUCCESS)
                    .actor(TenantContext.requireUser().userId())
                    .business(business.getId())
                    .target(AuditTarget.BUSINESS, business.getId())
                    .with(AuditDetail.REASON, allowed ? "ALLOW_NEGATIVE_STOCK_ENABLED" : "ALLOW_NEGATIVE_STOCK_DISABLED"));
        }
        return view(business);
    }

    private Business activeBusiness() {
        UUID businessId = TenantContext.requireBusinessId();
        return businesses.findById(businessId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}
