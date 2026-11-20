package com.fooyre.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** La garde n'est active qu'en profil prod, et une configuration invalide empêche le démarrage. */
class ProductionProfileStartupTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FooyreProperties.class)
    static class GuardOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GuardOnly.class, ProductionConfigurationGuard.class)
            .withPropertyValues(
                    "fooyre.public-base-url=http://localhost:5173",
                    "fooyre.default-timezone=Africa/Dakar",
                    "fooyre.default-currency=XOF",
                    "spring.datasource.password=change-me-app-password",
                    "spring.flyway.password=change-me-owner-password");

    @Test
    void prodProfileWithExampleValuesFailsToStart() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void guardIsInactiveOutsideProd() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ProductionConfigurationGuard.class));
    }

    @Test
    void invalidApplicationPropertiesPreventStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(GuardOnly.class)
                .withPropertyValues("fooyre.public-base-url=http://localhost:5173",
                        "fooyre.default-timezone=Africa/Dakar", "fooyre.default-currency=franc")
                .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner()
                .withUserConfiguration(GuardOnly.class)
                .withPropertyValues("fooyre.default-timezone=Africa/Dakar", "fooyre.default-currency=XOF")
                .run(context -> assertThat(context).hasFailed());
    }
}
