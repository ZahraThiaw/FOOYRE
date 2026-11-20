package com.fooyre.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Fige la matrice des permissions (docs/architecture.md §5.3, D3, D12, D14, D20). Toute
 * modification de {@link RolePermissions} doit mettre à jour la documentation ET ce test.
 */
class RolePermissionsTest {

    private static Set<String> names(Set<Permission> permissions) {
        return permissions.stream().map(Enum::name).collect(Collectors.toSet());
    }

    @Test
    void employeeMatrix() {
        assertThat(names(RolePermissions.of(BusinessRole.EMPLOYEE))).containsExactlyInAnyOrder(
                "BUSINESS_READ", "CATALOG_READ", "STOCK_READ", "CUSTOMER_READ", "CUSTOMER_WRITE", "SALE_CREATE",
                "PAYMENT_CREATE", "SALE_READ_ALL", "SALE_DISCOUNT_LIMITED", "ORDER_REQUEST_MANAGE", "DASHBOARD_OWN_DAY");
    }

    @Test
    void managerMatrix() {
        assertThat(names(RolePermissions.of(BusinessRole.BUSINESS_MANAGER))).containsExactlyInAnyOrder(
                "BUSINESS_READ", "BUSINESS_PROFILE_UPDATE", "BUSINESS_SETTINGS_READ", "SALES_POLICY_MANAGE",
                "STOREFRONT_MANAGE", "CATALOG_READ", "CATALOG_WRITE", "COST_READ", "STOCK_READ", "STOCK_ADJUST",
                "SUPPLIER_MANAGE", "PURCHASE_MANAGE", "CUSTOMER_READ", "CUSTOMER_WRITE", "CUSTOMER_BALANCE_READ",
                "CUSTOMER_ARCHIVE", "SALE_CREATE", "PAYMENT_CREATE", "SALE_READ_ALL", "SALE_PRICE_OVERRIDE",
                "SALE_DISCOUNT_UNLIMITED", "SALE_CANCEL", "PAYMENT_VOID", "REFUND_CREATE", "CUSTOMER_CREDIT_USE",
                "ORDER_REQUEST_MANAGE", "EXPENSE_MANAGE", "TREASURY_MANAGE", "FINANCE_READ", "REPORT_EXPORT",
                "DASHBOARD_FULL", "DASHBOARD_OWN_DAY", "AI_USE");
    }

    @Test
    void ownerHasManagerPermissionsPlusOwnerOnlyOnes() {
        Set<Permission> ownerOnly = EnumSet.copyOf(RolePermissions.of(BusinessRole.BUSINESS_OWNER));
        ownerOnly.removeAll(RolePermissions.of(BusinessRole.BUSINESS_MANAGER));
        assertThat(names(ownerOnly)).containsExactlyInAnyOrder("BUSINESS_SETTINGS_UPDATE", "MEMBER_MANAGE",
                "SUPPORT_ACCESS_GRANT", "AI_CONSENT_MANAGE", "TREASURY_OWNER_MOVEMENT");
        assertThat(RolePermissions.of(BusinessRole.BUSINESS_OWNER))
                .containsAll(RolePermissions.of(BusinessRole.BUSINESS_MANAGER))
                .doesNotContain(Permission.SALE_DISCOUNT_LIMITED);
    }

    @Test
    void noBusinessRoleGetsAPlatformPermission() {
        for (BusinessRole role : BusinessRole.values()) {
            assertThat(RolePermissions.of(role)).noneMatch(permission -> permission.name().startsWith("PLATFORM_"));
        }
    }

    @Test
    void noRoleMeansNoPermission() {
        assertThat(RolePermissions.of(null)).isEmpty();
    }

    @Test
    void permissionNamesMatchTheFrontendList() {
        // Même liste, même ordre que frontend/src/features/session/types.ts (PERMISSIONS).
        assertThat(Arrays.stream(Permission.values()).map(Enum::name)).containsExactly(
                "BUSINESS_READ", "BUSINESS_PROFILE_UPDATE", "BUSINESS_SETTINGS_READ", "BUSINESS_SETTINGS_UPDATE",
                "SALES_POLICY_MANAGE", "MEMBER_MANAGE", "SUPPORT_ACCESS_GRANT", "AI_CONSENT_MANAGE", "STOREFRONT_MANAGE",
                "CATALOG_READ", "CATALOG_WRITE", "COST_READ", "STOCK_READ", "STOCK_ADJUST", "SUPPLIER_MANAGE",
                "PURCHASE_MANAGE", "CUSTOMER_READ", "CUSTOMER_WRITE", "CUSTOMER_BALANCE_READ", "CUSTOMER_ARCHIVE",
                "SALE_CREATE", "PAYMENT_CREATE", "SALE_READ_ALL", "SALE_PRICE_OVERRIDE", "SALE_DISCOUNT_UNLIMITED",
                "SALE_DISCOUNT_LIMITED", "SALE_CANCEL", "PAYMENT_VOID", "REFUND_CREATE", "CUSTOMER_CREDIT_USE",
                "ORDER_REQUEST_MANAGE", "EXPENSE_MANAGE", "TREASURY_MANAGE", "TREASURY_OWNER_MOVEMENT", "FINANCE_READ",
                "REPORT_EXPORT", "DASHBOARD_FULL", "DASHBOARD_OWN_DAY", "AI_USE", "PLATFORM_BUSINESS_MANAGE",
                "PLATFORM_PLAN_MANAGE", "PLATFORM_AUDIT_READ", "PLATFORM_AI_USAGE_READ");
    }
}
