package com.fooyre;

import org.springframework.boot.SpringApplication;

/**
 * Démarrage local SANS PostgreSQL installé : l'application utilise un conteneur PostgreSQL
 * Testcontainers (Docker requis). Profil `test` : aucune donnée réelle.
 * Lancement proposé, NON VÉRIFIÉ (jamais exécuté) : {@code ./mvnw spring-boot:test-run -Dspring-boot.run.profiles=test}.
 */
public class TestFooyreApplication {

    public static void main(String[] args) {
        SpringApplication.from(FooyreApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
