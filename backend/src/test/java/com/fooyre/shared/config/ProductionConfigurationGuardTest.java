package com.fooyre.shared.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Refus de démarrer en production avec une configuration non sûre (sans révéler les secrets). */
class ProductionConfigurationGuardTest {

    private static final FooyreProperties HTTPS =
            new FooyreProperties(URI.create("https://app.fooyre.test"), ZoneId.of("Africa/Dakar"), "XOF");

    private static MockEnvironment environment(String appPassword, String migrationPassword) {
        MockEnvironment environment = new MockEnvironment();
        if (appPassword != null) {
            environment.setProperty("spring.datasource.password", appPassword);
        }
        if (migrationPassword != null) {
            environment.setProperty("spring.flyway.password", migrationPassword);
        }
        // MVP-1 : paramètres d'authentification conformes (valeurs de test, non secrètes).
        environment.setProperty("fooyre.auth.jwt-secret", "dGVzdC1vbmx5LWtleS1mb29yZS1tdnAxLTMyLWJ5dGVzISE=");
        environment.setProperty("fooyre.auth.cookie-secure", "true");
        return environment;
    }

    @Test
    void refusesAnExampleJwtSecretAndANonSecureCookie() {
        MockEnvironment env = environment("k7Q-random-1", "k7Q-random-2");
        env.setProperty("fooyre.auth.jwt-secret", "REPLACE_WITH_BASE64_RANDOM_SECRET_AT_LEAST_32_BYTES");
        env.setProperty("fooyre.auth.cookie-secure", "false");
        var guard = new ProductionConfigurationGuard(env, HTTPS);
        assertThatThrownBy(guard::afterPropertiesSet)
                .hasMessageContaining("fooyre.auth.jwt-secret contient une valeur d'exemple")
                .hasMessageContaining("fooyre.auth.cookie-secure (AUTH_COOKIE_SECURE) doit valoir true")
                .hasMessageNotContaining("REPLACE_WITH");
    }

    @Test
    void acceptsANonExampleConfiguration() {
        var guard = new ProductionConfigurationGuard(environment("k7Q-random-1", "k7Q-random-2"), HTTPS);
        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void refusesExampleValuesFromEnvExampleWithoutRevealingThem() {
        var guard = new ProductionConfigurationGuard(
                environment("change-me-app-password", "change-me-owner-password"), HTTPS);
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.password contient une valeur d'exemple")
                .hasMessageContaining("spring.flyway.password contient une valeur d'exemple")
                .hasMessageNotContaining("change-me-app-password");
    }

    @Test
    void refusesMissingSecrets() {
        var guard = new ProductionConfigurationGuard(environment(null, " "), HTTPS);
        assertThatThrownBy(guard::afterPropertiesSet)
                .hasMessageContaining("spring.datasource.password est vide")
                .hasMessageContaining("spring.flyway.password est vide");
    }

    @Test
    void refusesAPublicUrlWithoutHttps() {
        var http = new FooyreProperties(URI.create("http://app.fooyre.test"), ZoneId.of("Africa/Dakar"), "XOF");
        var guard = new ProductionConfigurationGuard(environment("k7Q-random-1", "k7Q-random-2"), http);
        assertThatThrownBy(guard::afterPropertiesSet).hasMessageContaining("HTTPS");
    }
}
