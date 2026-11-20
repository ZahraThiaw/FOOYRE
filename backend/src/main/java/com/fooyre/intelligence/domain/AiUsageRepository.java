package com.fooyre.intelligence.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Utilisation de l'IA, toujours filtrée par entreprise (quotas vérifiés AVANT l'appel). */
public interface AiUsageRepository extends JpaRepository<AiUsage, UUID> {

    long countByBusinessIdAndCreatedAtGreaterThanEqual(UUID businessId, Instant from);

    @Query("SELECT coalesce(sum(u.estimatedCostUsd), 0) FROM AiUsage u WHERE u.businessId = :businessId AND u.createdAt >= :from")
    BigDecimal costSince(UUID businessId, Instant from);
}
