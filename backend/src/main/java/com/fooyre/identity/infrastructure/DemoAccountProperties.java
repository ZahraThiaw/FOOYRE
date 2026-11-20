package com.fooyre.identity.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Compte de démonstration du profil {@code dev} ({@code fooyre.demo.*}). Le mot de passe vient
 * UNIQUEMENT de la variable d'environnement {@code FOOYRE_DEMO_OWNER_PASSWORD} (fichier .env local,
 * jamais versionné) : aucune valeur dans les sources ni dans une migration.
 */
@ConfigurationProperties(prefix = "fooyre.demo")
public record DemoAccountProperties(
        String ownerEmail,
        String ownerFullName,
        String ownerPassword,
        String businessName) {

    @Override
    public String toString() {
        return "DemoAccountProperties[ownerEmail=" + ownerEmail + ", ownerFullName=" + ownerFullName
                + ", ownerPassword=<masqué>, businessName=" + businessName + "]";
    }
}
