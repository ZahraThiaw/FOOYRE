package com.fooyre.business.api;

import com.fooyre.business.application.BusinessSettingsService;
import com.fooyre.business.application.BusinessSettingsService.BusinessSettingsView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paramètres sensibles de l'entreprise active ({@code /business/settings}, docs/api-specification.md
 * §2.2, D20) : lecture {@code BUSINESS_SETTINGS_READ} (OWNER, MANAGER), modification
 * {@code BUSINESS_SETTINGS_UPDATE} (OWNER seul). MVP-3 : {@code allowNegativeStock} ; les
 * paramètres fiscaux s'ajouteront à ce même contrat.
 */
@RestController
@RequestMapping("/api/v1/business/settings")
public class BusinessSettingsController {

    public record SettingsResponse(boolean allowNegativeStock, boolean vatEnabled, java.math.BigDecimal vatRate,
            String priceEntryMode, java.math.BigDecimal employeeMaxDiscountRate) {

        static SettingsResponse of(BusinessSettingsView view) {
            return new SettingsResponse(view.allowNegativeStock(), view.vatEnabled(), view.vatRate(),
                    view.priceEntryMode(), view.employeeMaxDiscountRate());
        }
    }

    /** Modification partielle (champ absent = inchangé). */
    public record SettingsRequest(Boolean allowNegativeStock, Boolean vatEnabled, java.math.BigDecimal vatRate,
            String priceEntryMode) {
    }

    private final BusinessSettingsService settings;

    BusinessSettingsController(BusinessSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('BUSINESS_SETTINGS_READ')")
    public SettingsResponse get() {
        return SettingsResponse.of(settings.activeSettings());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('BUSINESS_SETTINGS_UPDATE')")
    public SettingsResponse update(@Valid @RequestBody SettingsRequest request) {
        return SettingsResponse.of(settings.update(new com.fooyre.business.application.BusinessSettingsService.SettingsCommand(
                request.allowNegativeStock(), request.vatEnabled(), request.vatRate(), request.priceEntryMode())));
    }
}
