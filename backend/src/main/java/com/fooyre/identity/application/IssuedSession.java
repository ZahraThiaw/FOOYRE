package com.fooyre.identity.application;

import com.fooyre.shared.security.IssuedAccessToken;
import java.time.Duration;

/**
 * Résultat d'une connexion ou d'un renouvellement : jeton d'accès (renvoyé dans le corps) et
 * nouveau refresh token en clair (posé UNIQUEMENT dans le cookie HttpOnly, jamais journalisé).
 */
public record IssuedSession(IssuedAccessToken accessToken, String refreshToken, Duration refreshTokenTtl) {

    @Override
    public String toString() {
        return "IssuedSession[accessToken=<masqué>, refreshToken=<masqué>, refreshTokenTtl=" + refreshTokenTtl + "]";
    }
}
