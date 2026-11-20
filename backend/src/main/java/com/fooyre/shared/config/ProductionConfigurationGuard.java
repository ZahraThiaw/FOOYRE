package com.fooyre.shared.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Profil {@code prod} : refuse de démarrer si la configuration est manifestement non sûre
 * (docs/architecture.md §11) : identifiants PostgreSQL, URL publique, clé JWT (valeur d'exemple)
 * et cookie de rafraîchissement sans attribut {@code Secure} ({@code AUTH_COOKIE_SECURE}, ADR-04).
 * La longueur et le format de la clé JWT sont contrôlés dans tous les profils par
 * {@link AuthProperties}. Les messages ne contiennent jamais la valeur d'un secret, seulement le
 * nom du paramètre.
 */
@Component
@Profile("prod")
class ProductionConfigurationGuard implements InitializingBean {

    /** Fragments des valeurs d'exemple de .env.example. */
    private static final List<String> PLACEHOLDER_MARKERS = List.of("change-me", "replace", "example");

    private static final List<String> SECRET_PROPERTIES = List.of(
            "spring.datasource.password",
            "spring.flyway.password",
            "fooyre.auth.jwt-secret");

    private static final String COOKIE_SECURE_PROPERTY = "fooyre.auth.cookie-secure";

    private final Environment environment;
    private final FooyreProperties properties;

    ProductionConfigurationGuard(Environment environment, FooyreProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();
        for (String property : SECRET_PROPERTIES) {
            String value = environment.getProperty(property);
            if (value == null || value.isBlank()) {
                problems.add(property + " est vide");
            } else if (isPlaceholder(value)) {
                problems.add(property + " contient une valeur d'exemple");
            }
        }
        if (!"https".equalsIgnoreCase(properties.publicBaseUrl().getScheme())) {
            problems.add("fooyre.public-base-url doit utiliser HTTPS en production");
        }
        if (!"true".equalsIgnoreCase(environment.getProperty(COOKIE_SECURE_PROPERTY, "").trim())) {
            problems.add(COOKIE_SECURE_PROPERTY + " (AUTH_COOKIE_SECURE) doit valoir true en production");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Configuration de production refusée : " + String.join(" ; ", problems));
        }
    }

    private static boolean isPlaceholder(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return PLACEHOLDER_MARKERS.stream().anyMatch(normalized::contains);
    }
}
