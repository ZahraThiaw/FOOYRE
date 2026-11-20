package com.fooyre.business.api;

import com.fooyre.business.application.BusinessProfileService;
import com.fooyre.business.application.BusinessProfileView;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entreprise active ({@code GET /api/v1/business}, docs/api-specification.md §2.2). Aucun
 * identifiant d'entreprise n'est accepté en entrée : l'entreprise est celle du jeton vérifié.
 */
@RestController
@RequestMapping("/api/v1/business")
public class BusinessController {

    /** Profil général (aucune donnée financière : lisible par tous les rôles avec BUSINESS_READ). */
    public record BusinessResponse(UUID id, String name, String legalName, String currency,
            String timezone, String defaultLocale) {

        static BusinessResponse of(BusinessProfileView view) {
            return new BusinessResponse(view.id(), view.name(), view.legalName(), view.currency(),
                    view.timezone(), view.defaultLocale());
        }
    }

    private final BusinessProfileService profiles;

    BusinessController(BusinessProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('BUSINESS_READ')")
    public BusinessResponse activeBusiness() {
        return BusinessResponse.of(profiles.activeBusinessProfile());
    }
}
