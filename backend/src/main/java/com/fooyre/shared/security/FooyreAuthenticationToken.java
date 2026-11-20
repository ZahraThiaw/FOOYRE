package com.fooyre.shared.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Authentification d'une requête par jeton d'accès vérifié : le principal est
 * l'{@link AuthenticatedUser}, les autorités sont ses permissions effectives. Le jeton lui‑même
 * n'est pas conservé ({@link #getCredentials()} renvoie {@code null}).
 */
public final class FooyreAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser user;

    public FooyreAuthenticationToken(AuthenticatedUser user) {
        super(user.permissions().stream().map(permission -> new SimpleGrantedAuthority(permission.name())).toList());
        this.user = user;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return user;
    }

    @Override
    public String getName() {
        return user.userId().toString();
    }
}
