package com.fooyre;

import static org.assertj.core.api.Assertions.assertThat;

import com.fooyre.shared.config.FooyreProperties;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;


/** Démarrage complet du contexte sur PostgreSQL 18 (Testcontainers). */
@IntegrationTest
class FooyreApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private FooyreProperties properties;

    @Test
    void contextStartsOnPostgreSql18() {
        String version = new JdbcTemplate(dataSource).queryForObject("SHOW server_version", String.class);
        assertThat(version).startsWith("18.7");
    }

    /** Migrations de l'application appliquées : V1 comptes et entreprises, V2 limitation et audit, V3 catalogue, V4 stock. */
    @Test
    void flywayAppliesTheApplicationMigrations() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("7");
        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Object::toString)
                .containsExactly("classpath:db/migration");
    }

    @Test
    void applicationPropertiesAreBoundAndValidated() {
        assertThat(properties.defaultCurrency()).isEqualTo("XOF");
        assertThat(properties.defaultTimezone()).isEqualTo(ZoneId.of("Africa/Dakar"));
        assertThat(properties.publicBaseUrl()).hasToString("http://localhost:5173");
    }

    @Test
    void noInMemoryUserIsConfigured() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }
}
