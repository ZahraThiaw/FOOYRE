package com.fooyre.identity.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Compteurs d'échecs de connexion. Mises à jour atomiques (aucune perte en cas de concurrence). */
public interface LoginThrottleRepository extends JpaRepository<LoginThrottle, String> {

    /** Clés actuellement bloquées parmi celles fournies. */
    @Query("SELECT t FROM LoginThrottle t WHERE t.keyHash IN :keyHashes AND t.blockedUntil > :now")
    List<LoginThrottle> findBlocked(Collection<String> keyHashes, Instant now);

    /**
     * Enregistre un échec : nouvelle fenêtre si la précédente a commencé avant {@code windowStart},
     * sinon incrément ; blocage jusqu'à {@code blockedUntil} dès que {@code threshold} est atteint.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO login_throttle (key_hash, key_type, failure_count, window_started_at, blocked_until, updated_at)
            VALUES (:keyHash, :keyType, 1, :now, CASE WHEN 1 >= :threshold THEN CAST(:blockedUntil AS timestamptz) END, :now)
            ON CONFLICT (key_hash) DO UPDATE SET
              failure_count = CASE WHEN login_throttle.window_started_at <= :windowStart THEN 1
                                   ELSE login_throttle.failure_count + 1 END,
              window_started_at = CASE WHEN login_throttle.window_started_at <= :windowStart THEN :now
                                       ELSE login_throttle.window_started_at END,
              blocked_until = CASE WHEN (CASE WHEN login_throttle.window_started_at <= :windowStart THEN 1
                                              ELSE login_throttle.failure_count + 1 END) >= :threshold
                                   THEN CAST(:blockedUntil AS timestamptz)
                                   ELSE login_throttle.blocked_until END,
              updated_at = :now""")
    void recordFailure(String keyHash, String keyType, int threshold, Instant now, Instant windowStart,
            Instant blockedUntil);

    @Modifying
    @Query("DELETE FROM LoginThrottle t WHERE t.keyHash IN :keyHashes")
    void reset(Collection<String> keyHashes);

    /** Supprime les compteurs inactifs depuis {@code before} et non bloqués. */
    @Modifying
    @Query("DELETE FROM LoginThrottle t WHERE t.updatedAt < :before AND (t.blockedUntil IS NULL OR t.blockedUntil < :now)")
    int deleteExpired(Instant before, Instant now);
}
