package com.fooyre.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fooyre.FooyreApplication;
import com.fooyre.TestcontainersConfiguration;
import com.fooyre.business.application.MembershipService;
import com.fooyre.identity.application.AuthenticationService;
import com.fooyre.identity.application.ProfileService;
import com.fooyre.identity.application.UserAccountService;
import com.fooyre.shared.security.AuthenticatedUser;
import com.fooyre.shared.security.RolePermissions;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Migrations sur une base PostgreSQL 18.7 VIDE, puis persistance après redémarrage de
 * l'application : deux démarrages successifs sur le même conteneur ; la migration déjà exécutée
 * n'est pas rejouée et les données créées au premier démarrage sont relues au second.
 */
class MigrationAndPersistenceIntegrationTest {

    private static final String PASSWORD = "Mot-de-passe-persistance-1";

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startEmptyDatabase() {
        postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    /**
     * Démarrage « réel » hors du cadre @SpringBootTest : les classes de test (@TestConfiguration,
     * @TestComponent), qui créeraient un autre conteneur, sont exclues comme dans un test Spring Boot.
     */
    private static ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(FooyreApplication.class)
                .initializers(context -> context.getBeanFactory()
                        .registerSingleton("excludeTestComponents", new ExcludeTestComponents()))
                .profiles("test")
                // Arguments de ligne de commande : prioritaires sur application.yml (port aléatoire,
                // indépendant d'un backend local éventuellement démarré sur 8080).
                .run("--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--server.port=0");
    }

    static final class ExcludeTestComponents extends TypeExcludeFilter {

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            AnnotationMetadata metadata = reader.getAnnotationMetadata();
            return metadata.hasAnnotation(TestComponent.class.getName())
                    || metadata.hasMetaAnnotation(TestComponent.class.getName());
        }

        @Override
        public boolean equals(Object other) {
            return other != null && other.getClass() == getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }

    @Test
    void migratesAnEmptyDatabaseAndKeepsDataAcrossRestarts() {
        String email;
        UUID userId;
        UUID businessId;

        // Premier démarrage : base vide → V1 appliquée ; création d'un compte et d'une entreprise.
        try (ConfigurableApplicationContext first = startApplication()) {
            Flyway flyway = first.getBean(Flyway.class);
            assertThat(flyway.info().applied()).hasSize(7);
            assertThat(flyway.info().applied()).extracting(info -> info.getScript())
                    .containsExactly("V1__identity_and_business.sql", "V2__login_throttle_and_audit_log.sql",
                            "V3__catalog_categories_and_products.sql", "V4__stock.sql",
                            "V5__stock_entry_unit_cost_and_refusal_audit.sql", "V6__customers.sql", "V7__sales_cost_and_ai.sql");

            JdbcTemplate jdbc = new JdbcTemplate(first.getBean(javax.sql.DataSource.class));
            assertThat(jdbc.queryForList("""
                    SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'
                    AND table_name <> 'flyway_schema_history' ORDER BY table_name""", String.class))
                    .containsExactly("ai_usage", "app_user", "audit_log", "business", "business_membership", "business_sequence", "category", "customer", "login_throttle",
                            "product", "product_stock", "refresh_token", "sale", "sale_line", "stock_movement");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isZero();

            email = "persistance-" + UUID.randomUUID() + "@test.fooyre.invalid";
            userId = first.getBean(UserAccountService.class).createAccount(email, "Compte Persistant", PASSWORD);
            businessId = first.getBean(MembershipService.class)
                    .createBusinessWithOwner("Entreprise Persistante", userId).businessId();

            // Unicité de l'e-mail insensible à la casse, au niveau de la base.
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO app_user (id, email, password_hash, full_name, created_at, updated_at)
                    VALUES (gen_random_uuid(), upper(?), 'x', 'Doublon', now(), now())""", email))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        // Second démarrage : aucune migration rejouée, données relues, connexion possible.
        try (ConfigurableApplicationContext second = startApplication()) {
            Flyway flyway = second.getBean(Flyway.class);
            assertThat(flyway.info().applied()).hasSize(7);
            assertThat(flyway.info().pending()).isEmpty();
            JdbcTemplate jdbc = new JdbcTemplate(second.getBean(javax.sql.DataSource.class));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                    .isEqualTo(7);

            List<com.fooyre.business.application.MembershipView> memberships =
                    second.getBean(MembershipService.class).activeMembershipsOf(userId);
            assertThat(memberships).singleElement().satisfies(membership -> {
                assertThat(membership.businessId()).isEqualTo(businessId);
                assertThat(membership.businessName()).isEqualTo("Entreprise Persistante");
            });

            assertThat(second.getBean(AuthenticationService.class).login(email, PASSWORD, "127.0.0.1").accessToken().value())
                    .isNotBlank();
            var me = second.getBean(ProfileService.class).me(new AuthenticatedUser(userId, UUID.randomUUID(),
                    businessId, memberships.getFirst().membershipId(), memberships.getFirst().role(),
                    RolePermissions.of(memberships.getFirst().role())));
            assertThat(me.fullName()).isEqualTo("Compte Persistant");
        }
    }
}
