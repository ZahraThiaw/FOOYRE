package com.fooyre.identity.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SECOND compte propriétaire de démonstration du profil {@code dev} ({@code fooyre.demo2.*}),
 * indépendant du premier (entreprise distincte, isolation multi‑entreprise ordinaire). Le mot de
 * passe vient UNIQUEMENT de {@code FOOYRE_DEMO2_OWNER_PASSWORD} (fichier .env local, jamais
 * versionné) : aucune valeur dans les sources, les exemples ni une migration.
 */
@ConfigurationProperties(prefix = "fooyre.demo2")
public record DemoSecondAccountProperties(
        String ownerEmail,
        String ownerFullName,
        String ownerPassword,
        String businessName) {

    @Override
    public String toString() {
        return "DemoSecondAccountProperties[ownerEmail=" + ownerEmail + ", ownerFullName=" + ownerFullName
                + ", ownerPassword=<masqué>, businessName=" + businessName + "]";
    }
}
