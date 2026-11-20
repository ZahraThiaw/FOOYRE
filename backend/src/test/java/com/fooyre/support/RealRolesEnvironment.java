package com.fooyre.support;

import com.fooyre.FooyreApplication;
import com.fooyre.TestcontainersConfiguration;
import java.nio.file.Path;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Environnement de test aux conditions RÉELLES de la RLS : PostgreSQL 18.7 initialisé par le script
 * de l'environnement local (infra/postgres/init/01-roles.sh), migrations Flyway exécutées par le rôle
 * PROPRIÉTAIRE, application connectée avec le rôle APPLICATIF (ni superutilisateur, ni BYPASSRLS, ni
 * propriétaire des tables), pool réduit à UNE connexion pour démontrer l'absence de fuite de contexte.
 */
public final class RealRolesEnvironment implements AutoCloseable {

    public static final String DB = "fooyre";
    public static final String OWNER = "fooyre_owner";
    public static final String APP = "fooyre_app";
    private static final String OWNER_PASSWORD = "test-owner-pw";
    private static final String APP_PASSWORD = "test-app-pw";

    private final PostgreSQLContainer postgres;
    private final ConfigurableApplicationContext application;

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

    private RealRolesEnvironment(PostgreSQLContainer postgres, ConfigurableApplicationContext application) {
        this.postgres = postgres;
        this.application = application;
    }

    public static RealRolesEnvironment start() {
        PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)
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
        ConfigurableApplicationContext application = new SpringApplicationBuilder(FooyreApplication.class)
                .initializers(context -> context.getBeanFactory()
                        .registerSingleton("excludeTestComponents", new ExcludeTestComponents()))
                .profiles("test")
                .run("--spring.datasource.url=" + url,
                        "--spring.datasource.username=" + APP,
                        "--spring.datasource.password=" + APP_PASSWORD,
                        "--spring.datasource.hikari.maximum-pool-size=1",
                        "--spring.flyway.user=" + OWNER,
                        "--spring.flyway.password=" + OWNER_PASSWORD,
                        "--server.port=0");
        return new RealRolesEnvironment(postgres, application);
    }

    public <T> T bean(Class<T> type) {
        return application.getBean(type);
    }

    @Override
    public void close() {
        application.close();
        postgres.stop();
    }
}
