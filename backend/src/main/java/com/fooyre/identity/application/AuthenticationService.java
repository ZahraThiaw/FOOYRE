package com.fooyre.identity.application;

import com.fooyre.business.application.MembershipService;
import com.fooyre.business.application.MembershipView;
import com.fooyre.identity.domain.RefreshToken;
import com.fooyre.identity.domain.RefreshTokenRepository;
import com.fooyre.identity.domain.RevocationReason;
import com.fooyre.identity.domain.UserAccount;
import com.fooyre.identity.domain.UserAccountRepository;
import com.fooyre.shared.audit.AuditAction;
import com.fooyre.shared.audit.AuditDetail;
import com.fooyre.shared.audit.AuditEvent;
import com.fooyre.shared.audit.AuditOutcome;
import com.fooyre.shared.audit.AuditService;
import com.fooyre.shared.audit.AuditTarget;
import com.fooyre.shared.config.AuthProperties;
import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import com.fooyre.shared.security.AccessTokenClaims;
import com.fooyre.shared.security.AccessTokenService;
import com.fooyre.shared.security.PasswordConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Connexion, renouvellement et déconnexion (ADR-04).
 * <ul>
 *   <li>Connexion : la limitation ({@link LoginThrottleService}) est vérifiée AVANT les
 *       identifiants ({@code 429 RATE_LIMITED}, identique que le compte existe ou non). E-mail
 *       inconnu, mot de passe erroné et compte inactif donnent la MÊME réponse
 *       ({@code 401 INVALID_CREDENTIALS}) et le même coût (bcrypt sur un haché factice si le
 *       compte n'existe pas). Un échec est compté et journalisé dans des transactions
 *       indépendantes (il subsiste malgré le refus) ; une réussite est journalisée dans la
 *       transaction qui ouvre la session.</li>
 *   <li>Renouvellement : rotation du refresh token à chaque usage, sous verrou ; réutilisation d'un
 *       jeton remplacé hors de la fenêtre de tolérance ⇒ révocation de toute la session.</li>
 *   <li>Déconnexion : révocation de toute la session ; les jetons d'accès de la session sont
 *       refusés dès la requête suivante ({@link SessionVerificationService}).</li>
 * </ul>
 * Aucun mot de passe, jeton ni e-mail n'est journalisé.
 */
@Service
public class AuthenticationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationService.class);

    /** Résultat interne de la vérification des identifiants. */
    private sealed interface Attempt {
        record Succeeded(IssuedSession session) implements Attempt {
        }

        record Failed(UUID knownUserId, String reason) implements Attempt {
        }
    }

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final MembershipService memberships;
    private final AccessTokenService accessTokens;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottleService throttle;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final AuthProperties properties;
    private final Clock clock;
    /** Haché bcrypt d'une valeur aléatoire : comparaison à coût égal quand le compte n'existe pas. */
    private final String dummyPasswordHash;

    AuthenticationService(UserAccountRepository users, RefreshTokenRepository refreshTokens,
            MembershipService memberships, AccessTokenService accessTokens, PasswordEncoder passwordEncoder,
            LoginThrottleService throttle, AuditService audit, PlatformTransactionManager transactionManager,
            AuthProperties properties, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.memberships = memberships;
        this.accessTokens = accessTokens;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(RefreshTokenCodec.newToken());
    }

    /**
     * Vérifie les identifiants et ouvre une session.
     *
     * @param clientIp adresse de connexion (adresse TCP, jamais un en‑tête non vérifié)
     * @throws ApiException {@code RATE_LIMITED} (avec délai) ou {@code INVALID_CREDENTIALS}
     */
    public IssuedSession login(String email, String password, String clientIp) {
        String normalizedEmail = UserAccount.normalizeEmail(email);
        LoginThrottleService.Keys keys = throttle.keysFor(normalizedEmail, clientIp);

        Optional<LoginThrottleService.Block> block = throttle.currentBlock(keys);
        if (block.isPresent()) {
            audit.recordIndependently(AuditEvent.of(AuditAction.LOGIN_RATE_LIMITED, AuditOutcome.DENIED)
                    .with(AuditDetail.LIMIT, block.get().type().name()));
            long seconds = Math.max(1, (block.get().remaining().toMillis() + 999) / 1000);
            throw new ApiException(ErrorCode.RATE_LIMITED, seconds);
        }

        Attempt attempt = transactions.execute(status -> authenticate(normalizedEmail, password, keys));
        if (attempt instanceof Attempt.Succeeded succeeded) {
            return succeeded.session();
        }
        Attempt.Failed failed = (Attempt.Failed) attempt;
        throttle.recordFailure(keys);
        AuditEvent event = AuditEvent.of(AuditAction.LOGIN_FAILED, AuditOutcome.FAILURE)
                .with(AuditDetail.REASON, failed.reason());
        if (failed.knownUserId() != null) {
            event.target(AuditTarget.USER, failed.knownUserId());
        }
        audit.recordIndependently(event);
        throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
    }

    /** Dans la transaction de connexion : rien n'est écrit en cas d'échec. */
    private Attempt authenticate(String normalizedEmail, String password, LoginThrottleService.Keys keys) {
        Optional<UserAccount> found = users.findByEmail(normalizedEmail);
        boolean passwordMatches = passwordMatches(password, found.map(UserAccount::getPasswordHash).orElse(dummyPasswordHash));
        if (found.isEmpty()) {
            return new Attempt.Failed(null, "UNKNOWN_ACCOUNT");
        }
        if (!passwordMatches) {
            return new Attempt.Failed(found.get().getId(), "BAD_PASSWORD");
        }
        if (!found.get().isActive()) {
            return new Attempt.Failed(found.get().getId(), "ACCOUNT_INACTIVE");
        }
        UserAccount user = found.get();
        Instant now = clock.instant();
        user.recordLogin(now);
        throttle.recordSuccess(keys);

        MembershipView active = selectActiveMembership(memberships.activeMembershipsOf(user.getId()));
        UUID sessionId = UUID.randomUUID();
        IssuedSession session = openSession(user, active, sessionId, now, null);
        audit.record(AuditEvent.of(AuditAction.LOGIN_SUCCEEDED, AuditOutcome.SUCCESS)
                .actor(user.getId())
                .business(active == null ? null : active.businessId())
                .target(AuditTarget.SESSION, sessionId));
        return new Attempt.Succeeded(session);
    }

    /** Renouvelle la session à partir du refresh token présenté (cookie). */
    @Transactional
    public RefreshOutcome refresh(String presentedToken) {
        if (!RefreshTokenCodec.isWellFormed(presentedToken)) {
            return new RefreshOutcome.Rejected();
        }
        Optional<RefreshToken> found = refreshTokens.findByTokenHashForUpdate(RefreshTokenCodec.hash(presentedToken));
        if (found.isEmpty()) {
            return new RefreshOutcome.Rejected();
        }
        RefreshToken token = found.get();
        Instant now = clock.instant();

        if (token.isRevoked() || token.isExpired(now)) {
            return new RefreshOutcome.Rejected();
        }
        if (token.isReplaced()) {
            if (!now.isAfter(token.getReplacedAt().plus(properties.refreshReuseGrace()))) {
                // Rafraîchissements simultanés (plusieurs onglets) : refus SANS révocation.
                return new RefreshOutcome.Rejected();
            }
            revokeSession(token, RevocationReason.REUSE_DETECTED, AuditAction.TOKEN_REUSE_DETECTED, now);
            LOGGER.warn("Réutilisation d'un refresh token détectée : session {} révoquée", token.getFamilyId());
            return new RefreshOutcome.Rejected();
        }

        Optional<UserAccount> user = users.findById(token.getUserId());
        if (user.isEmpty() || !user.get().isActive()) {
            revokeSession(token, RevocationReason.ACCOUNT_DISABLED, AuditAction.SESSION_REVOKED, now);
            return new RefreshOutcome.Rejected();
        }

        MembershipView active = null;
        if (token.getMembershipId() != null) {
            Optional<MembershipView> membership = memberships.findActiveMembership(token.getMembershipId(), token.getUserId());
            if (membership.isEmpty()) {
                revokeSession(token, RevocationReason.MEMBERSHIP_REVOKED, AuditAction.SESSION_REVOKED, now);
                return new RefreshOutcome.Rejected();
            }
            active = membership.get();
        }
        return new RefreshOutcome.Renewed(openSession(user.get(), active, token.getFamilyId(), now, token));
    }

    /** Révoque la session du refresh token présenté, s'il est reconnu. Idempotent. */
    @Transactional
    public void logout(String presentedToken) {
        if (!RefreshTokenCodec.isWellFormed(presentedToken)) {
            return;
        }
        refreshTokens.findByTokenHashForUpdate(RefreshTokenCodec.hash(presentedToken)).ifPresent(token -> {
            if (refreshTokens.revokeFamily(token.getFamilyId(), RevocationReason.LOGOUT, clock.instant()) > 0) {
                audit.record(sessionEvent(AuditAction.LOGOUT, AuditOutcome.SUCCESS, token));
            }
        });
    }

    /** Révocation décidée par le serveur ; journalisée dans la même transaction. */
    private void revokeSession(RefreshToken token, RevocationReason reason, AuditAction action, Instant now) {
        refreshTokens.revokeFamily(token.getFamilyId(), reason, now);
        audit.record(sessionEvent(action, AuditOutcome.DENIED, token).with(AuditDetail.REASON, reason.name()));
    }

    private static AuditEvent sessionEvent(AuditAction action, AuditOutcome outcome, RefreshToken token) {
        return AuditEvent.of(action, outcome)
                .actor(token.getUserId())
                .business(token.getBusinessId())
                .target(AuditTarget.SESSION, token.getFamilyId());
    }

    /**
     * Entreprise active à la connexion : l'unique appartenance active. Avec plusieurs
     * appartenances, aucune n'est choisie à la place de l'utilisateur ({@code activeBusiness}
     * nul, choix par {@code POST /auth/switch-business}, lot suivant).
     */
    private static MembershipView selectActiveMembership(List<MembershipView> active) {
        return active.size() == 1 ? active.getFirst() : null;
    }

    private IssuedSession openSession(UserAccount user, MembershipView membership, UUID sessionId, Instant now,
            RefreshToken predecessor) {
        String refreshValue = RefreshTokenCodec.newToken();
        RefreshToken refreshToken = refreshTokens.save(new RefreshToken(user.getId(),
                membership == null ? null : membership.businessId(),
                membership == null ? null : membership.membershipId(),
                sessionId, RefreshTokenCodec.hash(refreshValue), now, properties.refreshTokenTtl()));
        if (predecessor != null) {
            predecessor.markReplacedBy(refreshToken, now);
        }
        AccessTokenClaims claims = new AccessTokenClaims(user.getId(), sessionId,
                membership == null ? null : membership.businessId(),
                membership == null ? null : membership.membershipId(),
                membership == null ? null : membership.role(),
                user.getSecurityVersion());
        return new IssuedSession(accessTokens.issue(claims), refreshValue, properties.refreshTokenTtl());
    }

    private boolean passwordMatches(String password, String hash) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > PasswordConfiguration.MAX_PASSWORD_BYTES) {
            passwordEncoder.matches(RefreshTokenCodec.newToken(), dummyPasswordHash); // coût équivalent
            return false;
        }
        return passwordEncoder.matches(password, hash);
    }
}
