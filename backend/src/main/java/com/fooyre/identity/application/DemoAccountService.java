package com.fooyre.identity.application;

import com.fooyre.business.application.MembershipService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compte propriétaire et entreprise de démonstration (développement local). Idempotent :
 * <ul>
 *   <li>compte absent → créé (mot de passe haché) ;</li>
 *   <li>compte présent → inchangé, mot de passe JAMAIS écrasé ;</li>
 *   <li>entreprise créée avec l'appartenance {@code BUSINESS_OWNER} uniquement si le compte n'est
 *       propriétaire d'aucune entreprise (pas de recréation à chaque démarrage).</li>
 * </ul>
 * Appelé uniquement par l'initialiseur du profil {@code dev} ; aucune donnée métier fictive.
 */
@Service
public class DemoAccountService {

    public enum Result { CREATED, BUSINESS_ADDED, ALREADY_PRESENT }

    private final UserAccountService accounts;
    private final MembershipService memberships;

    DemoAccountService(UserAccountService accounts, MembershipService memberships) {
        this.accounts = accounts;
        this.memberships = memberships;
    }

    @Transactional
    public Result ensureDemoAccount(String email, String fullName, String rawPassword, String businessName) {
        Optional<UUID> existing = accounts.findIdByEmail(email);
        UUID userId = existing.orElseGet(() -> accounts.createAccount(email, fullName, rawPassword));
        if (memberships.ownsABusiness(userId)) {
            return Result.ALREADY_PRESENT;
        }
        memberships.createBusinessWithOwner(businessName, userId);
        return existing.isPresent() ? Result.BUSINESS_ADDED : Result.CREATED;
    }
}
