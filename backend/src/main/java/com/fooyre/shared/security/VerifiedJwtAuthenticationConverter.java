package com.fooyre.shared.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Transforme un JWT cryptographiquement valide en authentification FOOYRE, APRÈS vérification en
 * base par {@link AccessTokenVerifier} (session non révoquée, compte et appartenance actifs…).
 * Tout échec lève une {@link InvalidBearerTokenException} → {@code 401 SESSION_REVOKED} ; le motif
 * précis n'est ni renvoyé ni journalisé avec le jeton.
 */
@Component
class VerifiedJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AccessTokenVerifier verifier;

    VerifiedJwtAuthenticationConverter(AccessTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AccessTokenClaims claims;
        try {
            claims = AccessTokenService.claimsOf(jwt);
        } catch (IllegalArgumentException | ArithmeticException malformed) {
            throw new InvalidBearerTokenException("Jeton invalide");
        }
        return verifier.verify(claims)
                .map(FooyreAuthenticationToken::new)
                .orElseThrow(() -> new InvalidBearerTokenException("Session révoquée"));
    }
}
