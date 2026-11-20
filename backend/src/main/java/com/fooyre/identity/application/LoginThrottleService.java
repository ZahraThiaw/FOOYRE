package com.fooyre.identity.application;

import com.fooyre.identity.domain.LoginThrottle;
import com.fooyre.identity.domain.LoginThrottleKeyType;
import com.fooyre.identity.domain.LoginThrottleRepository;
import com.fooyre.shared.security.KeyedHasher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Limitation des tentatives de connexion, persistée dans PostgreSQL (survit aux redémarrages,
 * partagée entre instances). Trois clés par tentative (voir {@link LoginThrottleKeyType}),
 * toutes sous forme d'empreinte HMAC ; l'e-mail est normalisé comme à l'authentification.
 * <p>
 * Anti‑blocage d'une victime : le blocage est temporaire ; le seuil par compte seul est plus
 * élevé que le seuil compte + IP, et une connexion réussie remet à zéro les compteurs du compte.
 * Limite connue : un attaquant distribué peut retarder la connexion d'un compte pendant la durée
 * de blocage, jamais au‑delà sans poursuivre l'attaque.
 */
@Service
public class LoginThrottleService {

    static final String HASH_PURPOSE = "login-throttle";

    /** Clés d'une tentative (empreintes). */
    public record Keys(String accountAndIp, String account, String ip) {

        List<String> all() {
            return List.of(accountAndIp, account, ip);
        }
    }

    /** Blocage en cours : dimension et délai restant. */
    public record Block(LoginThrottleKeyType type, Duration remaining) {
    }

    private final LoginThrottleRepository throttles;
    private final KeyedHasher hasher;
    private final LoginThrottleProperties properties;
    private final Clock clock;

    LoginThrottleService(LoginThrottleRepository throttles, KeyedHasher hasher, LoginThrottleProperties properties,
            Clock clock) {
        this.throttles = throttles;
        this.hasher = hasher;
        this.properties = properties;
        this.clock = clock;
    }

    /** @param normalizedEmail e-mail déjà normalisé ; @param clientIp adresse de connexion */
    public Keys keysFor(String normalizedEmail, String clientIp) {
        String ip = clientIp == null ? "" : clientIp;
        return new Keys(
                hasher.hash(HASH_PURPOSE, "ACCOUNT_IP:" + normalizedEmail + "\n" + ip),
                hasher.hash(HASH_PURPOSE, "ACCOUNT:" + normalizedEmail),
                hasher.hash(HASH_PURPOSE, "IP:" + ip));
    }

    /** Blocage le plus long en cours parmi les trois clés, s'il existe. */
    @Transactional(readOnly = true)
    public Optional<Block> currentBlock(Keys keys) {
        Instant now = clock.instant();
        return throttles.findBlocked(keys.all(), now).stream()
                .max(Comparator.comparing(LoginThrottle::getBlockedUntil))
                .map(throttle -> new Block(throttle.getKeyType(), Duration.between(now, throttle.getBlockedUntil())));
    }

    /** Échec d'identifiants : incrémente les trois compteurs, dans une transaction indépendante. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Keys keys) {
        Instant now = clock.instant();
        Instant windowStart = now.minus(properties.window());
        Instant blockedUntil = now.plus(properties.blockDuration());
        throttles.recordFailure(keys.accountAndIp(), LoginThrottleKeyType.ACCOUNT_IP.name(),
                properties.maxFailuresPerAccountAndIp(), now, windowStart, blockedUntil);
        throttles.recordFailure(keys.account(), LoginThrottleKeyType.ACCOUNT.name(),
                properties.maxFailuresPerAccount(), now, windowStart, blockedUntil);
        throttles.recordFailure(keys.ip(), LoginThrottleKeyType.IP.name(),
                properties.maxFailuresPerIp(), now, windowStart, blockedUntil);
    }

    /**
     * Connexion réussie : remet à zéro les compteurs du COMPTE (compte + IP, compte seul), dans la
     * transaction de la connexion. Le compteur de l'IP n'est pas remis à zéro (une réussite sur
     * un compte ne doit pas autoriser de nouveaux essais sur d'autres comptes).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccess(Keys keys) {
        throttles.reset(List.of(keys.accountAndIp(), keys.account()));
    }

    /** Supprime les compteurs inactifs depuis plus que la durée de conservation. */
    @Transactional
    public int purgeExpired() {
        Instant now = clock.instant();
        return throttles.deleteExpired(now.minus(properties.retention()), now);
    }
}
