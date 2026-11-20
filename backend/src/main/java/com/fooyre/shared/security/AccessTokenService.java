package com.fooyre.shared.security;

import com.fooyre.shared.config.AuthProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.stereotype.Component;

/**
 * Émission et lecture des jetons d'accès (ADR-04) : JWT signé HS256, clé fournie par
 * {@code JWT_SECRET}, durée {@code JWT_ACCESS_TOKEN_TTL_MINUTES}. Claims : {@code iss},
 * {@code sub} (utilisateur), {@code sid} (session), {@code bid} / {@code mid} / {@code role}
 * (contexte entreprise, absents si aucune entreprise active), {@code ver} (version de sécurité),
 * {@code jti}, {@code iat}, {@code exp}. Aucune donnée personnelle (ni e-mail ni nom) dans le jeton.
 */
@Component
public class AccessTokenService {

    static final String ISSUER = "fooyre";
    static final String CLAIM_SESSION = "sid";
    static final String CLAIM_BUSINESS = "bid";
    static final String CLAIM_MEMBERSHIP = "mid";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_SECURITY_VERSION = "ver";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final AuthProperties properties;
    private final Clock clock;

    public AccessTokenService(AuthProperties properties, Clock clock) {
        SecretKey key = new SecretKeySpec(properties.jwtSecretBytes(), "HmacSHA256");
        this.encoder = NimbusJwtEncoder.withSecretKey(key).build();
        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        nimbusDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(ISSUER)));
        this.decoder = nimbusDecoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedAccessToken issue(AccessTokenClaims claims) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(claims.userId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim(CLAIM_SESSION, claims.sessionId().toString())
                .claim(CLAIM_SECURITY_VERSION, claims.securityVersion());
        if (claims.businessId() != null) {
            builder.claim(CLAIM_BUSINESS, claims.businessId().toString())
                    .claim(CLAIM_MEMBERSHIP, claims.membershipId().toString())
                    .claim(CLAIM_ROLE, claims.role().name());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
        return new IssuedAccessToken(value, properties.accessTokenTtl().toSeconds());
    }

    /** Décodeur utilisé par la chaîne Spring Security (signature, algorithme, émetteur, expiration). */
    JwtDecoder decoder() {
        return decoder;
    }

    /**
     * Claims métier d'un jeton déjà validé cryptographiquement.
     *
     * @throws IllegalArgumentException si un claim est absent ou mal formé
     */
    static AccessTokenClaims claimsOf(Jwt jwt) {
        UUID userId = UUID.fromString(required(jwt.getSubject()));
        UUID sessionId = UUID.fromString(required(jwt.getClaimAsString(CLAIM_SESSION)));
        Object version = jwt.getClaim(CLAIM_SECURITY_VERSION);
        if (!(version instanceof Number number)) {
            throw new IllegalArgumentException("ver absent");
        }
        String business = jwt.getClaimAsString(CLAIM_BUSINESS);
        String membership = jwt.getClaimAsString(CLAIM_MEMBERSHIP);
        String role = jwt.getClaimAsString(CLAIM_ROLE);
        return new AccessTokenClaims(
                userId,
                sessionId,
                business == null ? null : UUID.fromString(business),
                membership == null ? null : UUID.fromString(membership),
                role == null ? null : BusinessRole.valueOf(role),
                Math.toIntExact(number.longValue()));
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("claim obligatoire absent");
        }
        return value;
    }
}
