package com.fooyre.gestion.application;

import com.fooyre.business.application.BusinessProfileService;
import com.fooyre.shared.security.AuthenticatedUser;
import com.fooyre.shared.security.Permission;
import com.fooyre.shared.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Contexte d'une opération du catalogue, issu UNIQUEMENT de l'utilisateur authentifié (jeton
 * revérifié) : entreprise active, acteur, droit de voir les coûts, devise de l'entreprise.
 */
@Component
class CatalogContext {

    record Current(UUID businessId, UUID actorId, boolean canReadCosts, String currency) {
    }

    private final BusinessProfileService businesses;

    CatalogContext(BusinessProfileService businesses) {
        this.businesses = businesses;
    }

    /** À appeler dans une transaction (lecture de la devise de l'entreprise active). */
    Current current() {
        AuthenticatedUser user = TenantContext.requireUser();
        UUID businessId = TenantContext.requireBusinessId();
        return new Current(businessId, user.userId(), user.permissions().contains(Permission.COST_READ),
                businesses.activeBusinessProfile().currency());
    }
}
