package com.fooyre;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Point d'entrée du backend FOOYRE (monolithe modulaire, docs/architecture.md ADR‑01). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FooyreApplication {

    public static void main(String[] args) {
        SpringApplication.run(FooyreApplication.class, args);
    }
}
