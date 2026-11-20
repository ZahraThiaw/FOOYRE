package com.fooyre.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * Utilisateur authentifié de la requête, établi par le backend après validation du jeton
 * d'accès ET vérification en base (compte actif, version de sécurité, session non révoquée,
 * appartenance active, entreprise active — ADR-04). Seule source du contexte entreprise.
 *
 * @param businessId   entreprise active, {@code null} si aucune
 * @param membershipId appartenance active, {@code null} si aucune
 * @param role         rôle dans l'entreprise active, {@code null} si aucune
 * @param permissions  permissions effectives, recalculées par le backend
 */
public record AuthenticatedUser(
        UUID userId,
        UUID sessionId,
        UUID businessId,
        UUID membershipId,
        BusinessRole role,
        Set<Permission> permissions) {

    public AuthenticatedUser {
        permissions = Set.copyOf(permissions);
    }

    public boolean hasActiveBusiness() {
        return businessId != null;
    }
}
