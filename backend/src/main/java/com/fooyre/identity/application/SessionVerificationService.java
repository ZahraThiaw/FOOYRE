package com.fooyre.identity.application;

import com.fooyre.business.application.MembershipService;
import com.fooyre.business.application.MembershipView;
import com.fooyre.identity.domain.RefreshTokenRepository;
import com.fooyre.identity.domain.UserAccount;
import com.fooyre.identity.domain.UserAccountRepository;
import com.fooyre.shared.security.AccessTokenClaims;
import com.fooyre.shared.security.AccessTokenVerifier;
import com.fooyre.shared.security.AuthenticatedUser;
import com.fooyre.shared.security.RolePermissions;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vérification en base de chaque jeton d'accès (ADR-04, « à chaque requête ») : compte actif,
 * {@code ver} égal à la version de sécurité courante, session non révoquée (déconnexion
 * effective immédiatement), appartenance {@code mid} active, sur une entreprise active, pour la
 * même entreprise et le même rôle. Les permissions sont RECALCULÉES depuis le rôle courant.
 */
@Service
class SessionVerificationService implements AccessTokenVerifier {

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final MembershipService memberships;

    SessionVerificationService(UserAccountRepository users, RefreshTokenRepository refreshTokens,
            MembershipService memberships) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.memberships = memberships;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthenticatedUser> verify(AccessTokenClaims claims) {
        Optional<UserAccount> user = users.findById(claims.userId());
        if (user.isEmpty() || !user.get().isActive() || user.get().getSecurityVersion() != claims.securityVersion()) {
            return Optional.empty();
        }
        if (!refreshTokens.isSessionOpen(claims.sessionId(), claims.userId())) {
            return Optional.empty();
        }
        if (claims.membershipId() == null) {
            return Optional.of(new AuthenticatedUser(claims.userId(), claims.sessionId(), null, null, null, Set.of()));
        }
        Optional<MembershipView> membership = memberships.findActiveMembership(claims.membershipId(), claims.userId());
        if (membership.isEmpty()
                || !membership.get().businessId().equals(claims.businessId())
                || membership.get().role() != claims.role()) {
            return Optional.empty();
        }
        MembershipView active = membership.get();
        return Optional.of(new AuthenticatedUser(claims.userId(), claims.sessionId(), active.businessId(),
                active.membershipId(), active.role(), RolePermissions.of(active.role())));
    }
}
