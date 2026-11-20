package com.fooyre.gestion.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Journal des mouvements (insertion et lecture seulement). Filtré par entreprise. */
public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    Optional<StockMovement> findByBusinessIdAndIdempotencyKey(UUID businessId, UUID idempotencyKey);

    Page<StockMovement> findByBusinessId(UUID businessId, Pageable pageable);

    Page<StockMovement> findByBusinessIdAndProductId(UUID businessId, UUID productId, Pageable pageable);
}
