package com.fooyre.gestion.domain;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * Produits. TOUTES les requêtes sont filtrées par {@code businessId} (en plus de la RLS) ;
 * {@code findById} seul est interdit (règle ArchUnit). Recherche paginée : {@link ProductSearch}.
 */
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    Optional<Product> findByIdAndBusinessId(UUID id, UUID businessId);

    /** Produit verrouillé ({@code SELECT … FOR UPDATE}) : sérialise l'initialisation de son stock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id AND p.businessId = :businessId")
    Optional<Product> lockByIdAndBusinessId(UUID id, UUID businessId);

    @Query("""
            SELECT count(p) > 0 FROM Product p
            WHERE p.businessId = :businessId AND p.status = com.fooyre.gestion.domain.CatalogStatus.ACTIVE
              AND lower(p.sku) = lower(:sku) AND (:excludedId IS NULL OR p.id <> :excludedId)""")
    boolean activeSkuExists(UUID businessId, String sku, UUID excludedId);

    @Query("""
            SELECT count(p) > 0 FROM Product p
            WHERE p.businessId = :businessId AND p.status = com.fooyre.gestion.domain.CatalogStatus.ACTIVE
              AND p.barcode = :barcode AND (:excludedId IS NULL OR p.id <> :excludedId)""")
    boolean activeBarcodeExists(UUID businessId, String barcode, UUID excludedId);

    /** Nombre de produits ACTIFS par catégorie, pour les catégories demandées. */
    @Query("""
            SELECT p.categoryId, count(p) FROM Product p
            WHERE p.businessId = :businessId AND p.categoryId IN :categoryIds
              AND p.status = com.fooyre.gestion.domain.CatalogStatus.ACTIVE
            GROUP BY p.categoryId""")
    List<Object[]> countActiveByCategory(UUID businessId, Collection<UUID> categoryIds);
}
