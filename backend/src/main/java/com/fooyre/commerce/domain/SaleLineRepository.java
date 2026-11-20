package com.fooyre.commerce.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Lignes de vente, toujours filtrées par entreprise. */
public interface SaleLineRepository extends JpaRepository<SaleLine, UUID> {

    List<SaleLine> findByBusinessIdAndSaleIdOrderByPosition(UUID businessId, UUID saleId);

    List<SaleLine> findByBusinessIdAndSaleIdIn(UUID businessId, Collection<UUID> saleIds);

    /** Remplacement des lignes d'un BROUILLON (la base refuse pour une vente confirmée). */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("DELETE FROM SaleLine l WHERE l.businessId = :businessId AND l.saleId = :saleId")
    int deleteDraftLines(UUID businessId, UUID saleId);
}
