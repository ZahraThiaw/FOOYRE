package com.fooyre.business.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Entreprises. {@code business} est la table du tenant lui‑même : la lecture par identifiant
 * n'est faite qu'avec un identifiant issu du contexte vérifié (appartenance active), jamais
 * avec un identifiant fourni par le client.
 */
public interface BusinessRepository extends JpaRepository<Business, UUID> {
}
