package com.fooyre.business.application;

import com.fooyre.business.domain.Business;
import com.fooyre.business.domain.BusinessMembership;
import com.fooyre.business.domain.BusinessMembershipRepository;
import com.fooyre.business.domain.BusinessRepository;
import com.fooyre.shared.audit.AuditAction;
import com.fooyre.shared.audit.AuditDetail;
import com.fooyre.shared.audit.AuditEvent;
import com.fooyre.shared.audit.AuditOutcome;
import com.fooyre.shared.audit.AuditService;
import com.fooyre.shared.audit.AuditTarget;
import com.fooyre.shared.config.FooyreProperties;
import com.fooyre.shared.security.BusinessRole;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service applicatif PUBLIC du module business pour les appartenances : seul point d'accès des
 * autres modules (identity) aux entreprises et appartenances. N'accepte jamais un identifiant
 * d'entreprise seul : toute lecture est liée à l'utilisateur.
 */
@Service
public class MembershipService {

    /** Langue par défaut d'une nouvelle entreprise (D16 : français seul au lancement). */
    static final String DEFAULT_LOCALE = "fr";

    private final BusinessRepository businesses;
    private final BusinessMembershipRepository memberships;
    private final FooyreProperties properties;
    private final AuditService audit;
    private final Clock clock;

    MembershipService(BusinessRepository businesses, BusinessMembershipRepository memberships,
            FooyreProperties properties, AuditService audit, Clock clock) {
        this.audit = audit;
        this.businesses = businesses;
        this.memberships = memberships;
        this.properties = properties;
        this.clock = clock;
    }

    /** Appartenances actives de l'utilisateur, sur des entreprises actives. */
    @Transactional(readOnly = true)
    public List<MembershipView> activeMembershipsOf(UUID userId) {
        return memberships.findActiveWithBusiness(userId).stream()
                .map(row -> toView((BusinessMembership) row[0], (Business) row[1]))
                .toList();
    }

    /**
     * Appartenance {@code membershipId} de l'utilisateur, si elle est active et que l'entreprise
     * est active ; vide sinon (appartenance révoquée, d'un autre utilisateur ou inexistante).
     */
    @Transactional(readOnly = true)
    public Optional<MembershipView> findActiveMembership(UUID membershipId, UUID userId) {
        return memberships.findActive(membershipId, userId)
                .flatMap(membership -> businesses.findById(membership.getBusinessId())
                        .map(business -> toView(membership, business)));
    }

    /** Vrai si l'utilisateur est propriétaire d'au moins une entreprise (quel que soit son statut). */
    @Transactional(readOnly = true)
    public boolean ownsABusiness(UUID userId) {
        return memberships.existsByUserIdAndRole(userId, BusinessRole.BUSINESS_OWNER);
    }

    /**
     * Crée une entreprise (devise et fuseau par défaut de la configuration) et l'appartenance
     * {@code BUSINESS_OWNER} de son propriétaire, dans la même transaction.
     */
    @Transactional
    public MembershipView createBusinessWithOwner(String businessName, UUID ownerUserId) {
        Instant now = clock.instant();
        Business business = businesses.save(new Business(businessName, properties.defaultCurrency(),
                properties.defaultTimezone().getId(), DEFAULT_LOCALE, now));
        BusinessMembership owner = memberships.save(
                new BusinessMembership(business.getId(), ownerUserId, BusinessRole.BUSINESS_OWNER, now));
        audit.record(AuditEvent.of(AuditAction.BUSINESS_CREATED, AuditOutcome.SUCCESS)
                .business(business.getId())
                .target(AuditTarget.BUSINESS, business.getId())
                .with(AuditDetail.ROLE, BusinessRole.BUSINESS_OWNER.name()));
        return toView(owner, business);
    }

    private static MembershipView toView(BusinessMembership membership, Business business) {
        return new MembershipView(membership.getId(), business.getId(), business.getName(),
                business.getCurrency(), membership.getRole());
    }
}
