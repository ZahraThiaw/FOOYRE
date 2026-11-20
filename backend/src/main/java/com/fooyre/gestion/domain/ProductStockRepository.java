package com.fooyre.gestion.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Stock courant des produits. Toutes les requêtes sont filtrées par entreprise (en plus de la RLS). */
public interface ProductStockRepository extends JpaRepository<ProductStock, UUID> {

    Optional<ProductStock> findByBusinessIdAndProductId(UUID businessId, UUID productId);

    boolean existsByBusinessIdAndProductId(UUID businessId, UUID productId);

    /**
     * Stock verrouillé ({@code SELECT … FOR UPDATE}) : les opérations concurrentes sur un même produit
     * sont sérialisées ; la quantité lue est celle sur laquelle le mouvement sera calculé.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProductStock s WHERE s.businessId = :businessId AND s.productId = :productId")
    Optional<ProductStock> lockByBusinessIdAndProductId(UUID businessId, UUID productId);
}
