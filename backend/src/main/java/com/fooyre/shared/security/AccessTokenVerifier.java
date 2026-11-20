package com.fooyre.shared.security;

import java.util.Optional;

/**
 * Port de vérification d'un jeton d'accès déjà validé cryptographiquement (signature,
 * expiration). Implémenté par le module {@code identity} : compte actif, {@code ver} égal à la
 * version de sécurité courante, session ({@code sid}) non révoquée, appartenance {@code mid}
 * active avec le même rôle, entreprise active (ADR-04, contrôle « à chaque requête »).
 */
public interface AccessTokenVerifier {

    /** Utilisateur authentifié, ou vide si le jeton ne doit plus être accepté. */
    Optional<AuthenticatedUser> verify(AccessTokenClaims claims);
}
