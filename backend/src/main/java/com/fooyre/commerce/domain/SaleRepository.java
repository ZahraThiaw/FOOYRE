package com.fooyre.commerce.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Ventes. TOUTES les requêtes sont filtrées par {@code businessId} (en plus de la RLS). */
public interface SaleRepository extends JpaRepository<Sale, UUID> {

    Optional<Sale> findByIdAndBusinessId(UUID id, UUID businessId);

    /** Verrou de la vente : confirmation et annulation sérialisées (pas de double traitement). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Sale s WHERE s.id = :id AND s.businessId = :businessId")
    Optional<Sale> lockByIdAndBusinessId(UUID id, UUID businessId);

    Optional<Sale> findByBusinessIdAndIdempotencyKey(UUID businessId, UUID idempotencyKey);

    Page<Sale> findByBusinessIdAndStatus(UUID businessId, SaleStatus status, Pageable pageable);

    Page<Sale> findByBusinessId(UUID businessId, Pageable pageable);

    Page<Sale> findByBusinessIdAndCustomerIdAndStatus(UUID businessId, UUID customerId, SaleStatus status,
            Pageable pageable);

    /** Agrégat des ventes CONFIRMÉES sur une période : [nombre, total TTC, total HT]. */
    @Query("""
            SELECT count(s), coalesce(sum(s.totalInclTax), 0), coalesce(sum(s.totalExclTax), 0) FROM Sale s
            WHERE s.businessId = :businessId AND s.status = com.fooyre.commerce.domain.SaleStatus.CONFIRMED
              AND s.confirmedAt >= :from AND s.confirmedAt < :to""")
    List<Object[]> confirmedTotals(UUID businessId, Instant from, Instant to);

    /** Quantités et montants vendus par produit (ventes confirmées de la période), du plus vendu au moins vendu. */
    @Query("""
            SELECT l.productId, max(l.productName), sum(l.quantity), sum(l.totalInclTax) FROM SaleLine l, Sale s
            WHERE s.id = l.saleId AND s.businessId = l.businessId AND s.businessId = :businessId
              AND s.status = com.fooyre.commerce.domain.SaleStatus.CONFIRMED
              AND s.confirmedAt >= :from AND s.confirmedAt < :to
            GROUP BY l.productId ORDER BY sum(l.quantity) DESC""")
    List<Object[]> topProducts(UUID businessId, Instant from, Instant to, Pageable pageable);

    /** Coût des marchandises vendues : [somme quantité × coût, lignes de coût inconnu]. */
    @Query("""
            SELECT coalesce(sum(l.quantity * l.unitCost), 0), sum(CASE WHEN l.unitCost IS NULL THEN 1 ELSE 0 END)
            FROM SaleLine l, Sale s
            WHERE s.id = l.saleId AND s.businessId = l.businessId AND s.businessId = :businessId
              AND s.status = com.fooyre.commerce.domain.SaleStatus.CONFIRMED
              AND s.confirmedAt >= :from AND s.confirmedAt < :to""")
    List<Object[]> costOfGoodsSold(UUID businessId, Instant from, Instant to);

}
