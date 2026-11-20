package com.fooyre.shared.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Paramètres applicatifs FOOYRE, validés au démarrage : une valeur manquante ou invalide
 * empêche l'application de démarrer.
 *
 * @param publicBaseUrl   origine publique de l'application (contrôle {@code Origin} des routes
 *                        à cookie — B4) ; HTTPS exigé en production
 * @param defaultTimezone fuseau horaire par défaut des entreprises (dates métier)
 * @param defaultCurrency devise par défaut des entreprises, code ISO 4217 (XOF au lancement)
 */
@Validated
@ConfigurationProperties(prefix = "fooyre")
public record FooyreProperties(
        @NotNull URI publicBaseUrl,
        @NotNull ZoneId defaultTimezone,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String defaultCurrency) {
}
