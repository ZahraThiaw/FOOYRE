package com.fooyre.business.api;

import com.fooyre.business.application.BusinessSettingsService;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Politique de vente ({@code /business/sales-policy}, D14) : limite de remise des employés, en %
 * du montant brut de la vente (forme PROVISOIRE, O11 ; 0 par défaut). {@code SALES_POLICY_MANAGE}
 * (OWNER, MANAGER) ; modification journalisée.
 */
@RestController
@RequestMapping("/api/v1/business/sales-policy")
public class SalesPolicyController {

    public record SalesPolicy(BigDecimal employeeMaxDiscountRate) {
    }

    public record SalesPolicyRequest(@NotNull BigDecimal employeeMaxDiscountRate) {
    }

    private final BusinessSettingsService settings;

    SalesPolicyController(BusinessSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SALES_POLICY_MANAGE')")
    public SalesPolicy get() {
        return new SalesPolicy(settings.salesSettings().employeeMaxDiscountRate());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SALES_POLICY_MANAGE')")
    public SalesPolicy update(@jakarta.validation.Valid @RequestBody SalesPolicyRequest request) {
        return new SalesPolicy(settings.updateEmployeeMaxDiscountRate(request.employeeMaxDiscountRate())
                .employeeMaxDiscountRate());
    }
}
