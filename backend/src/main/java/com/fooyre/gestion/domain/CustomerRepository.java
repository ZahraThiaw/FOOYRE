package com.fooyre.gestion.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Clients. TOUTES les requêtes sont filtrées par {@code businessId} (en plus de la RLS) ;
 * {@code findById} seul est interdit (règle ArchUnit). Recherche : {@link CustomerSearch}.
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    Optional<Customer> findByIdAndBusinessId(UUID id, UUID businessId);

    Optional<Customer> findByBusinessIdAndIdempotencyKey(UUID businessId, UUID idempotencyKey);
}
