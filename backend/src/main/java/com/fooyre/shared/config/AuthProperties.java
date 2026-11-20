package com.fooyre.shared.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Paramètres d'authentification (ADR-04), validés au démarrage. Les valeurs viennent des
 * variables d'environnement ({@code JWT_SECRET}, {@code JWT_ACCESS_TOKEN_TTL_MINUTES},
 * {@code JWT_REFRESH_TOKEN_TTL_DAYS}, {@code AUTH_REFRESH_REUSE_GRACE_SECONDS},
 * {@code AUTH_COOKIE_SECURE}) ; aucune valeur secrète par défaut.
 *
 * @param jwtSecret         clé HMAC du jeton d'accès, encodée en Base64, au moins 32 octets
 * @param accessTokenTtl    durée de validité du jeton d'accès (15 min)
 * @param refreshTokenTtl   durée de validité d'un refresh token (30 jours proposés)
 * @param refreshReuseGrace fenêtre de tolérance des rafraîchissements simultanés
 * @param cookieSecure      attribut {@code Secure} du cookie (obligatoire en production)
 */
@Validated
@ConfigurationProperties(prefix = "fooyre.auth")
public record AuthProperties(
        @NotBlank String jwtSecret,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotNull Duration refreshReuseGrace,
        boolean cookieSecure) {

    /** Taille minimale de la clé HS256 (256 bits). */
    public static final int MIN_SECRET_BYTES = 32;

    /** Clé décodée ; la valeur n'est jamais journalisée ni recopiée dans un message. */
    public byte[] jwtSecretBytes() {
        return Base64.getDecoder().decode(jwtSecret.trim());
    }

    @AssertTrue(message = "fooyre.auth.jwt-secret doit être une valeur Base64 d'au moins 32 octets")
    boolean isJwtSecretValid() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            return true; // signalé par @NotBlank
        }
        try {
            return jwtSecretBytes().length >= MIN_SECRET_BYTES;
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }

    @AssertTrue(message = "les durées d'authentification doivent être strictement positives")
    boolean isDurationsPositive() {
        return accessTokenTtl == null || refreshTokenTtl == null || refreshReuseGrace == null
                || (accessTokenTtl.isPositive() && refreshTokenTtl.isPositive() && !refreshReuseGrace.isNegative());
    }

    @Override
    public String toString() {
        return "AuthProperties[jwtSecret=<masqué>, accessTokenTtl=" + accessTokenTtl
                + ", refreshTokenTtl=" + refreshTokenTtl + ", refreshReuseGrace=" + refreshReuseGrace
                + ", cookieSecure=" + cookieSecure + "]";
    }
}
