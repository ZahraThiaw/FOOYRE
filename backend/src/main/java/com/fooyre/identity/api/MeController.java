package com.fooyre.identity.api;

import com.fooyre.business.application.MembershipView;
import com.fooyre.identity.application.MeView;
import com.fooyre.identity.application.ProfileService;
import com.fooyre.shared.security.BusinessRole;
import com.fooyre.shared.security.Permission;
import com.fooyre.shared.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profil de l'utilisateur connecté — contrat validé de {@code GET /me}
 * (docs/api-specification.md §2.1). Les rôles et permissions renvoyés servent à l'affichage ;
 * le backend revérifie chaque requête.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    public record ActiveBusiness(UUID id, String name, String currency) {
    }

    /** {@code employeeMaxDiscountRate} : renseigné pour EMPLOYEE à partir du lot ventes (D14, O11) ; null en MVP-1. */
    public record ActiveMembership(UUID id, BusinessRole role, String employeeMaxDiscountRate) {
    }

    public record BusinessSummary(UUID id, String name, BusinessRole role) {
    }

    public record MeResponse(
            UUID id,
            String email,
            String fullName,
            String locale,
            boolean platformAdmin,
            ActiveBusiness activeBusiness,
            ActiveMembership activeMembership,
            List<String> permissions,
            List<BusinessSummary> businesses) {

        static MeResponse of(MeView view) {
            MembershipView active = view.activeMembership();
            return new MeResponse(
                    view.id(),
                    view.email(),
                    view.fullName(),
                    view.locale(),
                    view.platformAdmin(),
                    active == null ? null : new ActiveBusiness(active.businessId(), active.businessName(), active.currency()),
                    active == null ? null : new ActiveMembership(active.membershipId(), active.role(), null),
                    view.permissions().stream().map(Permission::name).sorted().toList(),
                    view.memberships().stream()
                            .map(membership -> new BusinessSummary(membership.businessId(), membership.businessName(),
                                    membership.role()))
                            .toList());
        }
    }

    private final ProfileService profiles;

    MeController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public MeResponse me() {
        return MeResponse.of(profiles.me(TenantContext.requireUser()));
    }
}
