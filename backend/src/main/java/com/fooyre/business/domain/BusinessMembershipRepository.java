package com.fooyre.business.domain;

import com.fooyre.shared.security.BusinessRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Appartenances. Toute lecture est filtrée par l'utilisateur (jamais par identifiant seul). */
public interface BusinessMembershipRepository extends JpaRepository<BusinessMembership, UUID> {

    /** Appartenance active de cet utilisateur, sur une entreprise active. */
    @Query("""
            SELECT m FROM BusinessMembership m, Business b
            WHERE m.id = :membershipId AND m.userId = :userId AND m.businessId = b.id
              AND m.status = com.fooyre.business.domain.MembershipStatus.ACTIVE
              AND b.status = com.fooyre.business.domain.BusinessStatus.ACTIVE""")
    Optional<BusinessMembership> findActive(UUID membershipId, UUID userId);

    /** Appartenances actives de l'utilisateur sur des entreprises actives, triées par nom d'entreprise. */
    @Query("""
            SELECT m, b FROM BusinessMembership m, Business b
            WHERE m.userId = :userId AND m.businessId = b.id
              AND m.status = com.fooyre.business.domain.MembershipStatus.ACTIVE
              AND b.status = com.fooyre.business.domain.BusinessStatus.ACTIVE
            ORDER BY b.name, b.id""")
    List<Object[]> findActiveWithBusiness(UUID userId);

    boolean existsByUserIdAndRole(UUID userId, BusinessRole role);
}
