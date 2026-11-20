package com.fooyre.identity.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Limitation des tentatives de connexion ({@code fooyre.auth.login-throttle.*}, variables
 * {@code LOGIN_THROTTLE_*} — voir .env.example). Le blocage est toujours TEMPORAIRE.
 *
 * @param maxFailuresPerAccountAndIp échecs tolérés pour un e-mail depuis une IP, dans la fenêtre
 * @param maxFailuresPerAccount      échecs tolérés pour un e-mail, toutes IP confondues
 * @param maxFailuresPerIp           échecs tolérés depuis une IP, tous e-mails confondus
 * @param window                     fenêtre de comptage
 * @param blockDuration              durée du blocage une fois un seuil atteint
 * @param retention                  durée de conservation d'un compteur inactif (nettoyage)
 */
@Validated
@ConfigurationProperties(prefix = "fooyre.auth.login-throttle")
public record LoginThrottleProperties(
        @Min(1) int maxFailuresPerAccountAndIp,
        @Min(1) int maxFailuresPerAccount,
        @Min(1) int maxFailuresPerIp,
        @NotNull Duration window,
        @NotNull Duration blockDuration,
        @NotNull Duration retention) {
}
