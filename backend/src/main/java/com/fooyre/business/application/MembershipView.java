package com.fooyre.business.application;

import com.fooyre.shared.security.BusinessRole;
import java.util.UUID;

/** Appartenance active d'un utilisateur, avec les informations publiques de l'entreprise. */
public record MembershipView(
        UUID membershipId,
        UUID businessId,
        String businessName,
        String currency,
        BusinessRole role) {
}
