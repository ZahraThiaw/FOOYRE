package com.fooyre.shared.security;

import java.util.UUID;

/**
 * Contenu d'un jeton d'accès (ADR-04) : {@code sub}, {@code sid}, {@code bid}, {@code mid},
 * {@code role}, {@code ver}. Les trois valeurs d'entreprise sont toutes renseignées ou toutes nulles.
 */
public record AccessTokenClaims(
        UUID userId,
        UUID sessionId,
        UUID businessId,
        UUID membershipId,
        BusinessRole role,
        int securityVersion) {

    public AccessTokenClaims {
        if (userId == null || sessionId == null) {
            throw new IllegalArgumentException("sub et sid sont obligatoires");
        }
        boolean noBusiness = businessId == null;
        if (noBusiness != (membershipId == null) || noBusiness != (role == null)) {
            throw new IllegalArgumentException("contexte entreprise incohérent");
        }
    }
}
