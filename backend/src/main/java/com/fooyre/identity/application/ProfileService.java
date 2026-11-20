package com.fooyre.identity.application;

import com.fooyre.business.application.MembershipService;
import com.fooyre.business.application.MembershipView;
import com.fooyre.identity.domain.UserAccount;
import com.fooyre.identity.domain.UserAccountRepository;
import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import com.fooyre.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profil {@code /me}, construit uniquement à partir de l'utilisateur authentifié (jeton vérifié)
 * et des données PostgreSQL. Les permissions renvoyées sont celles calculées par le backend.
 */
@Service
public class ProfileService {

    private final UserAccountRepository users;
    private final MembershipService memberships;

    ProfileService(UserAccountRepository users, MembershipService memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public MeView me(AuthenticatedUser authenticated) {
        UserAccount user = users.findById(authenticated.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_REVOKED));
        List<MembershipView> active = memberships.activeMembershipsOf(user.getId());
        MembershipView current = authenticated.membershipId() == null ? null : active.stream()
                .filter(membership -> membership.membershipId().equals(authenticated.membershipId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_REVOKED));
        return new MeView(user.getId(), user.getEmail(), user.getFullName(), user.getLocale(),
                user.isPlatformAdmin(), current, authenticated.permissions(), active);
    }
}
