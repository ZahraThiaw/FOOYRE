package com.fooyre.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fooyre.TestcontainersConfiguration;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Vérifie, sur PostgreSQL 18.7 réel, le script d'initialisation de l'environnement local
 * (infra/postgres/init/01-roles.sh) avec les mêmes paramètres que docker-compose.yml :
 * rôles propriétaire / applicatif, privilèges, encodage UTF‑8, fuseau UTC.
 * Valeurs de test uniquement (aucun secret réel).
 */
class PostgresInitScriptIntegrationTest {

    private static final String DB = "fooyre";
    private static final String OWNER = "fooyre_owner";
    private static final String OWNER_PASSWORD = "test-owner-pw";
    private static final String APP = "fooyre_app";
    private static final String APP_PASSWORD = "test-app-pw";

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startPostgresWithInitScript() {
        postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)
                .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8")
                .withEnv("TZ", "UTC")
                .withEnv("PGTZ", "UTC")
                .withEnv("FOOYRE_DB_NAME", DB)
                .withEnv("FOOYRE_OWNER_USER", OWNER)
                .withEnv("FOOYRE_OWNER_PASSWORD", OWNER_PASSWORD)
                .withEnv("FOOYRE_APP_USER", APP)
                .withEnv("FOOYRE_APP_PASSWORD", APP_PASSWORD)
                .withCommand("postgres", "-c", "timezone=UTC", "-c", "log_timezone=UTC")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(Path.of("..", "infra", "postgres", "init", "01-roles.sh")),
                        "/docker-entrypoint-initdb.d/01-roles.sh");
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    private static Connection connect(String user, String password) throws SQLException {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + DB;
        return DriverManager.getConnection(url, user, password);
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void databaseIsUtf8WithBuiltinLocaleOwnedByTheOwnerRoleAndInUtc() throws SQLException {
        try (Connection owner = connect(OWNER, OWNER_PASSWORD)) {
            assertThat(queryString(owner, "SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = 'fooyre'"))
                    .isEqualTo("UTF8");
            assertThat(queryString(owner, "SELECT datlocprovider FROM pg_database WHERE datname = 'fooyre'"))
                    .isEqualTo("b");
            assertThat(queryString(owner, "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'fooyre'"))
                    .isEqualTo(OWNER);
            assertThat(queryString(owner, "SHOW log_timezone")).isEqualTo("UTC");
            assertThat(queryString(owner, "SHOW server_version")).startsWith("18.7");
        }
    }

    /**
     * Fuseau par défaut du SERVEUR. Le pilote JDBC fixe le fuseau de SA session d'après la JVM
     * cliente : on interroge donc le serveur avec psql, sans variable PGTZ.
     */
    @Test
    void serverDefaultTimezoneIsUtc() throws Exception {
        var result = postgres.execInContainer("env", "-u", "PGTZ", "-u", "TZ",
                "psql", "-U", OWNER, "-d", DB, "-tAc", "SHOW timezone");
        // Socket local du conteneur (authentification locale de l'image officielle).
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        assertThat(result.getStdout().trim()).isEqualTo("UTC");
    }

    @Test
    void neitherRoleHasAdministrativePrivileges() throws SQLException {
        try (Connection owner = connect(OWNER, OWNER_PASSWORD)) {
            String flags = """
                    SELECT string_agg(rolname || ':' || rolsuper || rolcreatedb || rolcreaterole || rolreplication
                                      || rolbypassrls, ',' ORDER BY rolname)
                    FROM pg_roles WHERE rolname IN ('fooyre_owner', 'fooyre_app')""";
            assertThat(queryString(owner, flags)).isEqualTo("fooyre_app:falsefalsefalsefalsefalse,"
                    + "fooyre_owner:falsefalsefalsefalsefalse");
        }
    }

    @Test
    void applicationRoleUsesDataButCannotChangeTheSchema() throws SQLException {
        try (Connection owner = connect(OWNER, OWNER_PASSWORD); Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE TABLE privilege_check (id INT PRIMARY KEY, label TEXT)");
            ddl.execute("CREATE SEQUENCE privilege_check_seq");
        }
        try (Connection app = connect(APP, APP_PASSWORD); Statement statement = app.createStatement()) {
            statement.execute("INSERT INTO privilege_check VALUES (1, 'é')");
            statement.execute("UPDATE privilege_check SET label = 'ü' WHERE id = 1");
            assertThat(queryString(app, "SELECT label FROM privilege_check WHERE id = 1")).isEqualTo("ü");
            assertThat(queryString(app, "SELECT nextval('privilege_check_seq')")).isEqualTo("1");
            statement.execute("DELETE FROM privilege_check WHERE id = 1");

            assertThatThrownBy(() -> statement.execute("CREATE TABLE intrusion (id INT)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("DROP TABLE privilege_check"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("must be owner");
            assertThatThrownBy(() -> statement.execute("ALTER TABLE privilege_check ADD COLUMN x INT"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("must be owner");
            assertThatThrownBy(() -> statement.execute("TRUNCATE privilege_check"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("CREATE TEMP TABLE scratch (id INT)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
        try (Connection owner = connect(OWNER, OWNER_PASSWORD); Statement ddl = owner.createStatement()) {
            ddl.execute("DROP TABLE privilege_check");
            ddl.execute("DROP SEQUENCE privilege_check_seq");
        }
    }

    @Test
    void wrongPasswordIsRejected() {
        assertThatThrownBy(() -> connect(APP, "mauvais-mot-de-passe").close())
                .isInstanceOf(SQLException.class);
    }
}
