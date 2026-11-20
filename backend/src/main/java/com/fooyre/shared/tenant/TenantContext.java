package com.fooyre.shared.tenant;

import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import com.fooyre.shared.security.AuthenticatedUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Contexte entreprise de la requête (CLAUDE.md §3) : le {@code businessId} vient UNIQUEMENT du
 * jeton d'accès validé et revérifié en base (appartenance {@code ACTIVE}, entreprise
 * {@code ACTIVE}). Aucun identifiant d'entreprise fourni par le client (URL, corps, en‑tête) n'est
 * jamais lu ici.
 * <p>
 * MVP-1 : socle minimal. {@code TenantScopedEntity}, FK composites généralisées et RLS
 * PostgreSQL arriveront avec les premières tables métier portant {@code business_id}.
 */
public final class TenantContext {

    private TenantContext() {
    }

    /** Utilisateur authentifié de la requête courante, s'il existe. */
    public static Optional<AuthenticatedUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /** Utilisateur authentifié ; {@code 401} sinon. */
    public static AuthenticatedUser requireUser() {
        return currentUser().orElseThrow(() -> new ApiException(ErrorCode.SESSION_REVOKED));
    }

    /** Entreprise active de la requête ; {@code 403} si l'utilisateur n'en a aucune. */
    public static UUID requireBusinessId() {
        AuthenticatedUser user = requireUser();
        if (!user.hasActiveBusiness()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return user.businessId();
    }
}
