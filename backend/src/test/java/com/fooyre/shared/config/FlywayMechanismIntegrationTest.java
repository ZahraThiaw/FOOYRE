package com.fooyre.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fooyre.IntegrationTest;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Mécanisme Flyway vérifié avec une migration artificielle isolée : la migration
 * utilisée ici n'existe que dans src/test/resources/db/test-migration et s'applique dans un
 * schéma de test dédié du conteneur PostgreSQL 18.
 */
@IntegrationTest
class FlywayMechanismIntegrationTest {

    private static final String TEST_SCHEMA = "flyway_mechanism_test";

    @Autowired
    private DataSource dataSource;

    /** MVP-1 : l'application livre ses migrations ; la migration de test reste hors de db/migration. */
    @Test
    void applicationMigrationsAreSeparateFromTheTestMigration() {
        assertThat(new ClassPathResource("db/migration/V1__identity_and_business.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V1__flyway_mechanism_check.sql").exists()).isFalse();
    }

    @Test
    void flywayAppliesAVersionedMigrationOnceAndRecordsIt() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(TEST_SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/test-migration")
                .load();

        MigrateResult first = flyway.migrate();
        MigrateResult second = flyway.migrate();

        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isEqualTo(1);
        assertThat(second.migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer history = jdbc.queryForObject(
                "SELECT count(*) FROM " + TEST_SCHEMA + ".flyway_schema_history WHERE success AND version = '1'",
                Integer.class);
        String amountType = jdbc.queryForObject("""
                SELECT data_type || '(' || numeric_precision || ',' || numeric_scale || ')'
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = 'flyway_mechanism_check' AND column_name = 'amount'""",
                String.class, TEST_SCHEMA);
        assertThat(history).isEqualTo(1);
        assertThat(amountType).isEqualTo("numeric(19,2)");
    }
}
