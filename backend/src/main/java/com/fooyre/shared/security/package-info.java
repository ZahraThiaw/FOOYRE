/**
 * Sécurité transverse : chaîne de filtres sans état, refus par défaut, réponses 401/403 en
 * Problem Details, jeton d'accès JWT HS256 (émission et validation), matrice rôle → permissions,
 * hachage bcrypt. La vérification en base de chaque jeton est déléguée au port
 * AccessTokenVerifier, implémenté par le module identity.
 */
package com.fooyre.shared.security;
