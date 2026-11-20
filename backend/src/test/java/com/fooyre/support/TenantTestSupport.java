package com.fooyre.support;

import com.fooyre.shared.security.AuthenticatedUser;
import com.fooyre.shared.security.BusinessRole;
import com.fooyre.shared.security.FooyreAuthenticationToken;
import com.fooyre.shared.security.RolePermissions;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContextHolder;

/** Exécute du code de test « en tant que » propriétaire d'une entreprise (contexte vérifié simulé). */
public final class TenantTestSupport {

    private TenantTestSupport() {
    }

    public static <T> T asBusiness(UUID businessId, Supplier<T> action) {
        return asUser(UUID.randomUUID(), businessId, action);
    }

    /** En tant qu'utilisateur EXISTANT (requis dès qu'une opération est journalisée : FK de l'audit). */
    public static <T> T asUser(UUID userId, UUID businessId, Supplier<T> action) {
        AuthenticatedUser user = new AuthenticatedUser(userId, UUID.randomUUID(), businessId,
                UUID.randomUUID(), BusinessRole.BUSINESS_OWNER, RolePermissions.of(BusinessRole.BUSINESS_OWNER));
        SecurityContextHolder.getContext().setAuthentication(new FooyreAuthenticationToken(user));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    public static void runAsBusiness(UUID businessId, Runnable action) {
        asBusiness(businessId, () -> {
            action.run();
            return null;
        });
    }
}
