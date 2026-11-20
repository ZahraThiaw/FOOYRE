package com.fooyre.gestion.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Catégories. TOUTES les requêtes sont filtrées par {@code businessId} (en plus de la RLS) ;
 * {@code findById} seul est interdit (règle ArchUnit).
 */
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByIdAndBusinessId(UUID id, UUID businessId);

    List<Category> findByBusinessIdAndIdIn(UUID businessId, Collection<UUID> ids);

    Page<Category> findByBusinessIdAndStatus(UUID businessId, CatalogStatus status, Pageable pageable);

    Page<Category> findByBusinessId(UUID businessId, Pageable pageable);

    /** Une autre catégorie ACTIVE porte déjà ce nom (sans tenir compte de la casse). */
    @Query("""
            SELECT count(c) > 0 FROM Category c
            WHERE c.businessId = :businessId AND c.status = com.fooyre.gestion.domain.CatalogStatus.ACTIVE
              AND lower(c.name) = lower(:name) AND (:excludedId IS NULL OR c.id <> :excludedId)""")
    boolean activeNameExists(UUID businessId, String name, UUID excludedId);
}
