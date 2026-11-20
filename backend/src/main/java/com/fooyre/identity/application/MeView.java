package com.fooyre.identity.application;

import com.fooyre.business.application.MembershipView;
import com.fooyre.shared.security.Permission;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Profil de l'utilisateur connecté (contrat {@code GET /me}, docs/api-specification.md §2.1).
 * {@code activeMembership} est nul si et seulement si aucune entreprise n'est active.
 */
public record MeView(
        UUID id,
        String email,
        String fullName,
        String locale,
        boolean platformAdmin,
        MembershipView activeMembership,
        Set<Permission> permissions,
        List<MembershipView> memberships) {
}
