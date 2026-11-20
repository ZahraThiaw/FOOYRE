package com.fooyre.shared.tenant;

import static com.fooyre.support.TenantTestSupport.asBusiness;
import static com.fooyre.support.TenantTestSupport.runAsBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fooyre.FooyreApplication;
import com.fooyre.TestcontainersConfiguration;
import com.fooyre.business.application.MembershipService;
import com.fooyre.identity.application.UserAccountService;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PREUVE de l'isolation PostgreSQL Row Level Security dans les conditions réelles : rôles créés par
 * le script d'initialisation de l'environnement (infra/postgres/init/01-roles.sh), migrations
 * exécutées par le rôle PROPRIÉTAIRE, application connectée avec le rôle APPLICATIF (ni
 * superutilisateur, ni BYPASSRLS, ni propriétaire des tables), pool réduit à UNE connexion pour
 * démontrer l'absence de fuite de contexte entre transactions successives.
 * La table {@code rls_probe} n'existe que dans les ressources de test (db/rls-probe).
 */
class RowLevelSecurityIntegrationTest {

    private static final String DB = "fooyre";
    private static final String OWNER = "fooyre_owner";
    private static final String OWNER_PASSWORD = "test-owner-pw";
    private static final String APP = "fooyre_app";
    private static final String APP_PASSWORD = "test-app-pw";

    private static PostgreSQLContainer postgres;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static UUID businessA;
    private static UUID businessB;

    /** Exclut les configurations de test (@TestConfiguration, @TestComponent) du démarrage manuel. */
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

    @BeforeAll
    static void startWithRealRoles() {
        postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)
                .withEnv("FOOYRE_DB_NAME", DB)
                .withEnv("FOOYRE_OWNER_USER", OWNER)
                .withEnv("FOOYRE_OWNER_PASSWORD", OWNER_PASSWORD)
                .withEnv("FOOYRE_APP_USER", APP)
                .withEnv("FOOYRE_APP_PASSWORD", APP_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(Path.of("..", "infra", "postgres", "init", "01-roles.sh")),
                        "/docker-entrypoint-initdb.d/01-roles.sh");
        postgres.start();
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + DB;
        app = new SpringApplicationBuilder(FooyreApplication.class)
                .initializers(context -> context.getBeanFactory()
                        .registerSingleton("excludeTestComponents", new ExcludeTestComponents()))
                .profiles("test")
                .run("--spring.datasource.url=" + url,
                        "--spring.datasource.username=" + APP,
                        "--spring.datasource.password=" + APP_PASSWORD,
                        "--spring.datasource.hikari.maximum-pool-size=1",
                        "--spring.flyway.user=" + OWNER,
                        "--spring.flyway.password=" + OWNER_PASSWORD,
                        "--spring.flyway.locations=classpath:db/migration,classpath:db/rls-probe",
                        "--server.port=0");
        jdbc = new JdbcTemplate(app.getBean(javax.sql.DataSource.class));
        transactions = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));

        UserAccountService accounts = app.getBean(UserAccountService.class);
        MembershipService memberships = app.getBean(MembershipService.class);
        businessA = memberships.createBusinessWithOwner("Entreprise RLS A",
                accounts.createAccount("rls-a@test.fooyre.invalid", "Propriétaire A", "Mot-de-passe-rls-1")).businessId();
        businessB = memberships.createBusinessWithOwner("Entreprise RLS B",
                accounts.createAccount("rls-b@test.fooyre.invalid", "Propriétaire B", "Mot-de-passe-rls-2")).businessId();

        insertAs(businessA, "a-1");
        insertAs(businessA, "a-2");
        insertAs(businessB, "b-1");
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
        postgres.stop();
    }

    private static UUID insertAs(UUID business, String label) {
        UUID id = UUID.randomUUID();
        runAsBusiness(business, () -> transactions.executeWithoutResult(status ->
                jdbc.update("INSERT INTO rls_probe (id, business_id, label) VALUES (?, ?, ?)", id, business, label)));
        return id;
    }

    private static Integer countAs(UUID business) {
        return asBusiness(business, () -> transactions.execute(status ->
                jdbc.queryForObject("SELECT count(*) FROM rls_probe", Integer.class)));
    }

    @Test
    void theApplicationRoleCannotBypassRowLevelSecurity() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP);
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT tableowner FROM pg_tables WHERE tablename = 'rls_probe'", String.class)).isEqualTo(OWNER);
        assertThat(jdbc.queryForObject(
                "SELECT relrowsecurity FROM pg_class WHERE relname = 'rls_probe'", Boolean.class)).isTrue();
    }

    @Test
    void eachBusinessSeesOnlyItsOwnRows() {
        assertThat(countAs(businessA)).isEqualTo(2);
        assertThat(countAs(businessB)).isGreaterThanOrEqualTo(1); // d'autres tests ajoutent des lignes à B
        java.util.List<String> labels = asBusiness(businessA, () -> transactions.execute(status ->
                jdbc.queryForList("SELECT label FROM rls_probe WHERE label LIKE 'a-%' ORDER BY label", String.class)));
        assertThat(labels).containsExactly("a-1", "a-2");
    }

    @Test
    void knowingAnIdentifierOfAnotherBusinessGivesNoAccess() {
        UUID bRow = insertAs(businessB, "b-cible");
        runAsBusiness(businessA, () -> transactions.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rls_probe WHERE id = ?", Integer.class, bRow)).isZero();
            assertThat(jdbc.update("UPDATE rls_probe SET label = 'pirate' WHERE id = ?", bRow)).isZero();
            assertThat(jdbc.update("DELETE FROM rls_probe WHERE id = ?", bRow)).isZero();
        }));
        String label = asBusiness(businessB, () -> transactions.execute(status ->
                jdbc.queryForObject("SELECT label FROM rls_probe WHERE id = ?", String.class, bRow)));
        assertThat(label).isEqualTo("b-cible");
    }

    @Test
    void writingARowForAnotherBusinessIsRefused() {
        assertThatThrownBy(() -> insertAsWrongBusiness())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    private static void insertAsWrongBusiness() {
        runAsBusiness(businessA, () -> transactions.executeWithoutResult(status ->
                jdbc.update("INSERT INTO rls_probe (id, business_id, label) VALUES (?, ?, 'intrus')",
                        UUID.randomUUID(), businessB)));
    }

    @Test
    void withoutContextNothingIsVisibleOrWritable() {
        // Transaction sans entreprise active, puis requête hors transaction (auto‑commit).
        Integer inTransaction = transactions.execute(status -> jdbc.queryForObject("SELECT count(*) FROM rls_probe", Integer.class));
        assertThat(inTransaction).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rls_probe", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO rls_probe (id, business_id, label) VALUES (?, ?, 'x')",
                UUID.randomUUID(), businessA)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void theContextDoesNotLeakThroughTheSinglePooledConnection() {
        for (int i = 0; i < 10; i++) {
            assertThat(countAs(businessA)).isEqualTo(2);
            // Même et unique connexion du pool, réutilisée juste après : plus aucun contexte.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rls_probe", Integer.class)).isZero();
            String setting = jdbc.queryForObject("SELECT current_setting('app.business_id', true)", String.class);
            assertThat(setting == null ? "" : setting).isEmpty();
            assertThat(countAs(businessB)).isGreaterThanOrEqualTo(1);
        }
    }
}
