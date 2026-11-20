package com.fooyre.shared.security;

import static com.fooyre.shared.security.Permission.AI_CONSENT_MANAGE;
import static com.fooyre.shared.security.Permission.AI_USE;
import static com.fooyre.shared.security.Permission.BUSINESS_PROFILE_UPDATE;
import static com.fooyre.shared.security.Permission.BUSINESS_READ;
import static com.fooyre.shared.security.Permission.BUSINESS_SETTINGS_READ;
import static com.fooyre.shared.security.Permission.BUSINESS_SETTINGS_UPDATE;
import static com.fooyre.shared.security.Permission.CATALOG_READ;
import static com.fooyre.shared.security.Permission.CATALOG_WRITE;
import static com.fooyre.shared.security.Permission.COST_READ;
import static com.fooyre.shared.security.Permission.CUSTOMER_ARCHIVE;
import static com.fooyre.shared.security.Permission.CUSTOMER_BALANCE_READ;
import static com.fooyre.shared.security.Permission.CUSTOMER_CREDIT_USE;
import static com.fooyre.shared.security.Permission.CUSTOMER_READ;
import static com.fooyre.shared.security.Permission.CUSTOMER_WRITE;
import static com.fooyre.shared.security.Permission.DASHBOARD_FULL;
import static com.fooyre.shared.security.Permission.DASHBOARD_OWN_DAY;
import static com.fooyre.shared.security.Permission.EXPENSE_MANAGE;
import static com.fooyre.shared.security.Permission.FINANCE_READ;
import static com.fooyre.shared.security.Permission.MEMBER_MANAGE;
import static com.fooyre.shared.security.Permission.ORDER_REQUEST_MANAGE;
import static com.fooyre.shared.security.Permission.PAYMENT_CREATE;
import static com.fooyre.shared.security.Permission.PAYMENT_VOID;
import static com.fooyre.shared.security.Permission.PURCHASE_MANAGE;
import static com.fooyre.shared.security.Permission.REFUND_CREATE;
import static com.fooyre.shared.security.Permission.REPORT_EXPORT;
import static com.fooyre.shared.security.Permission.SALES_POLICY_MANAGE;
import static com.fooyre.shared.security.Permission.SALE_CANCEL;
import static com.fooyre.shared.security.Permission.SALE_CREATE;
import static com.fooyre.shared.security.Permission.SALE_DISCOUNT_LIMITED;
import static com.fooyre.shared.security.Permission.SALE_DISCOUNT_UNLIMITED;
import static com.fooyre.shared.security.Permission.SALE_PRICE_OVERRIDE;
import static com.fooyre.shared.security.Permission.SALE_READ_ALL;
import static com.fooyre.shared.security.Permission.STOCK_ADJUST;
import static com.fooyre.shared.security.Permission.STOCK_READ;
import static com.fooyre.shared.security.Permission.STOREFRONT_MANAGE;
import static com.fooyre.shared.security.Permission.SUPPLIER_MANAGE;
import static com.fooyre.shared.security.Permission.SUPPORT_ACCESS_GRANT;
import static com.fooyre.shared.security.Permission.TREASURY_MANAGE;
import static com.fooyre.shared.security.Permission.TREASURY_OWNER_MOVEMENT;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Correspondance rôle → permissions, point UNIQUE (docs/architecture.md §5.3). Figée par
 * {@code RolePermissionsTest} : toute modification met à jour la documentation et ce test.
 * Les permissions {@code PLATFORM_*} ne sont attribuées à aucun rôle d'entreprise (D12).
 */
public final class RolePermissions {

    private static final Map<BusinessRole, Set<Permission>> MATRIX = new EnumMap<>(BusinessRole.class);

    static {
        Set<Permission> employee = EnumSet.of(
                BUSINESS_READ, CATALOG_READ, STOCK_READ, CUSTOMER_READ, CUSTOMER_WRITE, SALE_CREATE,
                PAYMENT_CREATE, SALE_READ_ALL, SALE_DISCOUNT_LIMITED, ORDER_REQUEST_MANAGE, DASHBOARD_OWN_DAY);

        Set<Permission> manager = EnumSet.of(
                BUSINESS_READ, BUSINESS_PROFILE_UPDATE, BUSINESS_SETTINGS_READ, SALES_POLICY_MANAGE,
                STOREFRONT_MANAGE, CATALOG_READ, CATALOG_WRITE, COST_READ, STOCK_READ, STOCK_ADJUST,
                SUPPLIER_MANAGE, PURCHASE_MANAGE, CUSTOMER_READ, CUSTOMER_WRITE, CUSTOMER_BALANCE_READ,
                CUSTOMER_ARCHIVE, SALE_CREATE, PAYMENT_CREATE, SALE_READ_ALL, SALE_PRICE_OVERRIDE,
                SALE_DISCOUNT_UNLIMITED, SALE_CANCEL, PAYMENT_VOID, REFUND_CREATE, CUSTOMER_CREDIT_USE,
                ORDER_REQUEST_MANAGE, EXPENSE_MANAGE, TREASURY_MANAGE, FINANCE_READ, REPORT_EXPORT,
                DASHBOARD_FULL, DASHBOARD_OWN_DAY, AI_USE);

        Set<Permission> owner = EnumSet.copyOf(manager);
        owner.addAll(EnumSet.of(BUSINESS_SETTINGS_UPDATE, MEMBER_MANAGE, SUPPORT_ACCESS_GRANT,
                AI_CONSENT_MANAGE, TREASURY_OWNER_MOVEMENT));

        MATRIX.put(BusinessRole.BUSINESS_OWNER, Collections.unmodifiableSet(owner));
        MATRIX.put(BusinessRole.BUSINESS_MANAGER, Collections.unmodifiableSet(manager));
        MATRIX.put(BusinessRole.EMPLOYEE, Collections.unmodifiableSet(employee));
    }

    private RolePermissions() {
    }

    /** Permissions d'un rôle d'entreprise ; aucune sans rôle (pas d'entreprise active). */
    public static Set<Permission> of(BusinessRole role) {
        return role == null ? Set.of() : MATRIX.get(role);
    }
}
